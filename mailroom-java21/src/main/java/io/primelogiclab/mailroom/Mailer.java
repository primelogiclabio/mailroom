package io.primelogiclab.mailroom;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.util.ByteArrayDataSource;
import org.eclipse.angus.mail.smtp.SMTPTransport;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends emails over a single, reused SMTP connection.
 *
 * <p>The connection is opened by {@link #open()} or lazily by the first {@link #send(Email)}, and stays
 * open until {@link #close()} is called, so sending many emails in a loop costs one connection, not one
 * per email. Use it in try-with-resources to have the connection closed automatically:
 *
 * <pre>{@code
 * try (Mailer mailer = new Mailer(config)) {
 *     for (Email email : emails) {
 *         mailer.send(email);
 *     }
 * }
 * }</pre>
 *
 * <p>Each email is tried up to {@link MailConfig#maxAttempts()} times (3 by default). After a failed
 * attempt the connection is dropped and re-established, so a connection the server closed while idle
 * recovers on its own. When every attempt fails a {@link MailException} is thrown.
 *
 * <p>After {@link #close()} the mailer can be used again; the next send opens a new connection.
 * Instances are thread-safe, including from virtual threads; sends are serialized over the one connection.
 *
 * <p><b>Logging</b> uses {@code java.util.logging}, which Spring Boot, Tomcat and the SLF4J bridge pick up.
 * Logger {@code io.primelogiclab.mailroom.Mailer} logs each connection (server, security, user), each
 * email sent (Message-ID and the server's reply) and each close at FINE (DEBUG), and failed attempts at
 * WARNING. With {@link MailConfig.Builder#debug(boolean)} those details are logged at INFO instead, and the
 * SMTP conversation is logged at INFO to {@code io.primelogiclab.mailroom.smtp}. Passwords and access tokens
 * are never logged.
 */
public class Mailer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Mailer.class.getName());
    /** Receives the SMTP conversation when {@link MailConfig#debug()} is on. */
    static final Logger SMTP_LOG = Logger.getLogger("io.primelogiclab.mailroom.smtp");

    private final MailConfig config;
    private final Session session;
    private final Level detailLevel;
    private final AtomicInteger connectionsOpened = new AtomicInteger();
    // A lock rather than synchronized: on Java 21 a virtual thread blocked on SMTP I/O inside
    // synchronized pins its carrier thread; with ReentrantLock it unmounts.
    private final ReentrantLock lock = new ReentrantLock();
    private final SmtpTraceStream trace;
    private Transport transport;
    private int sentOnConnection;

    public Mailer(MailConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.session = Session.getInstance(config.toProperties());
        this.detailLevel = config.debug() ? Level.INFO : Level.FINE;
        this.trace = config.debug() ? new SmtpTraceStream(SMTP_LOG, Level.INFO) : null;
        if (config.debug()) {
            // The mail library writes its protocol trace to this stream; send it to the logger instead
            // of stdout. Login commands are left out of the trace (mail.debug.auth stays false),
            // and SmtpTraceStream leaves out message bodies.
            session.setDebugOut(new PrintStream(trace, true, StandardCharsets.UTF_8));
            session.setDebug(true);
        }
    }

    /** Convenience: open a mailer, send one email, close it. Prefer a shared {@link Mailer} for multiple emails. */
    public static void sendOnce(MailConfig config, Email email) {
        try (Mailer mailer = new Mailer(config)) {
            mailer.send(email);
        }
    }

    public MailConfig config() {
        return config;
    }

    /**
     * Opens the SMTP connection now (with retries) instead of on the first send.
     * Useful to fail fast on bad settings or credentials. Does nothing if already connected.
     *
     * @return this mailer, for chaining
     * @throws MailException if no connection could be made after all attempts
     */
    public Mailer open() {
        lock.lock();
        try {
            withRetry("connect to " + config.host() + ":" + config.port(), this::ensureConnected);
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** Whether an SMTP connection is currently open. Does not contact the server. */
    public boolean isOpen() {
        lock.lock();
        try {
            return transport != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sends an email, opening the connection first if needed.
     *
     * @throws MailException if the email is invalid, or could not be sent after all attempts
     */
    public void send(Email email) {
        Objects.requireNonNull(email, "email");
        MimeMessage message = toMimeMessage(email); // built outside the lock, in parallel across threads
        lock.lock();
        try {
            withRetry("send email \"" + email.subject() + "\"", () -> {
                ensureConnected();
                long start = System.nanoTime();
                transport.sendMessage(message, message.getAllRecipients());
                sentOnConnection++;
                if (LOG.isLoggable(detailLevel)) {
                    LOG.log(detailLevel, "Sent \"" + email.subject() + "\" " + message.getMessageID()
                            + " to " + message.getAllRecipients().length + " recipient(s) via " + server()
                            + " in " + millisSince(start) + " ms; server replied: " + lastServerResponse());
                }
            });
        } finally {
            lock.unlock();
        }
    }

    /** Closes the SMTP connection if open. Safe to call more than once. */
    @Override
    public void close() {
        lock.lock();
        try {
            disconnect();
        } finally {
            lock.unlock();
        }
    }

    /** Number of SMTP connections opened by this mailer so far. Visible for tests. */
    int connectionsOpened() {
        return connectionsOpened.get();
    }

    // ---------------------------------------------------------------- internals

    @FunctionalInterface
    private interface SmtpAction {
        void run() throws MessagingException;
    }

    private void withRetry(String what, SmtpAction action) {
        List<MessagingException> failures = new ArrayList<>();
        int max = config.maxAttempts();

        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                action.run();
                return;
            } catch (MessagingException e) {
                failures.add(e);
                disconnect();

                if (isPartiallyDelivered(e)) {
                    // Some recipients already got it; retrying would send them duplicates.
                    break;
                }
                if (attempt < max) {
                    LOG.log(Level.WARNING, "Attempt " + attempt + "/" + max + " to " + what + " via " + server()
                            + " failed: " + oneLine(e) + "; retrying");
                    pause();
                }
            }
        }

        MessagingException last = failures.get(failures.size() - 1);
        MailException ex = new MailException(
                "Failed to " + what + " after " + failures.size() + " attempt(s): " + oneLine(last), last);
        for (int i = 0; i < failures.size() - 1; i++) {
            ex.addSuppressed(failures.get(i));
        }
        throw ex;
    }

    private static boolean isPartiallyDelivered(MessagingException e) {
        if (e instanceof SendFailedException sfe) {
            Address[] sent = sfe.getValidSentAddresses();
            return sent != null && sent.length > 0;
        }
        return false;
    }

    private void pause() {
        long millis = config.retryDelay().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MailException("Interrupted while waiting to retry", ie);
        }
    }

    private void ensureConnected() throws MessagingException {
        // Transport.isConnected() sends a NOOP, so a connection dropped by the server is detected here.
        if (transport != null && transport.isConnected()) {
            return;
        }
        if (transport != null) {
            LOG.log(detailLevel, "Connection to " + server() + " was closed by the server; reconnecting");
        }
        disconnect();
        if (trace != null) {
            trace.reset();
        }

        String login = !config.hasCredentials() ? "without login"
                : "as " + config.username() + (config.usesOAuth2() ? " with OAuth 2.0" : "");
        LOG.log(detailLevel, "Connecting to " + server() + " " + login);
        long start = System.nanoTime();
        Transport t = session.getTransport(config.protocol());
        if (config.hasCredentials()) {
            t.connect(config.host(), config.port(), config.username(), secret());
        } else {
            t.connect(config.host(), config.port(), null, null);
        }
        transport = t;
        sentOnConnection = 0;
        int n = connectionsOpened.incrementAndGet();
        if (LOG.isLoggable(detailLevel)) {
            LOG.log(detailLevel, "Connected to " + server() + " " + login + " in " + millisSince(start)
                    + " ms (connection #" + n + ", encrypted: " + isEncrypted(t) + ")");
        }
    }

    /** The password, or a current OAuth 2.0 access token from the config's supplier. */
    private String secret() throws MessagingException {
        if (!config.usesOAuth2()) {
            return config.password();
        }
        String token;
        try {
            token = config.accessToken().get();
        } catch (RuntimeException e) {
            throw new MessagingException("Could not get an OAuth 2.0 access token: " + oneLine(e), e);
        }
        if (token == null || token.trim().isEmpty()) {
            throw new MessagingException("The OAuth 2.0 access token supplier returned no token");
        }
        return token.trim();
    }

    private void disconnect() {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (MessagingException e) {
            LOG.log(Level.FINE, "Ignoring error while closing SMTP connection", e);
        } finally {
            transport = null;
            LOG.log(detailLevel, "Closed connection to " + server() + " after " + sentOnConnection + " email(s)");
        }
    }

    /** Host, port and security, e.g. {@code smtp.example.com:587 (STARTTLS)}. */
    private String server() {
        return config.host() + ":" + config.port() + " (" + config.security() + ")";
    }

    private String lastServerResponse() {
        if (transport instanceof SMTPTransport smtp) {
            String reply = smtp.getLastServerResponse();
            return reply == null ? "(none)" : reply.trim();
        }
        return "(unknown)";
    }

    /** Whether the live socket is TLS, from the start (TLS) or after a STARTTLS upgrade. */
    private static boolean isEncrypted(Transport t) {
        return t instanceof SMTPTransport smtp && smtp.isSSL();
    }

    /** The exception message on one line; server replies end with a line break. */
    private static String oneLine(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getName() : m.trim();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Logs the mail library's protocol trace line by line. Message headers are kept; the message body
     * (text and attachments) is replaced by a line count, so logs stay small and content stays private.
     */
    private static final class SmtpTraceStream extends OutputStream {
        private enum State { COMMANDS, HEADERS, BODY }

        private final Logger logger;
        private final Level level;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private State state = State.COMMANDS;
        private int bodyLines;

        SmtpTraceStream(Logger logger, Level level) {
            this.logger = logger;
            this.level = level;
        }

        /** Back to command mode, e.g. for a new connection after one failed mid-message. */
        synchronized void reset() {
            line.reset();
            state = State.COMMANDS;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                endLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        private void endLine() {
            String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
            line.reset();
            switch (state) {
                case COMMANDS:
                    log(text);
                    if (text.startsWith("354")) { // server is ready for the message: headers come next
                        state = State.HEADERS;
                    }
                    break;
                case HEADERS:
                    if (text.isEmpty()) {
                        state = State.BODY;
                        bodyLines = 0;
                    } else {
                        log(text);
                        if (text.equals(".")) {
                            state = State.COMMANDS;
                        }
                    }
                    break;
                case BODY:
                    if (text.equals(".") || text.startsWith("DEBUG")) { // end of message, or the library reporting an error
                        logger.log(level, "(message body: " + bodyLines + " line(s), not logged)");
                        log(text);
                        state = State.COMMANDS;
                    } else {
                        bodyLines++;
                    }
                    break;
                default:
                    throw new IllegalStateException(state.name());
            }
        }

        private void log(String text) {
            if (!text.isEmpty()) {
                logger.log(level, text);
            }
        }
    }

    private MimeMessage toMimeMessage(Email email) {
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(parse(email.from()));
            for (String a : email.to()) msg.addRecipient(Message.RecipientType.TO, parse(a));
            for (String a : email.cc()) msg.addRecipient(Message.RecipientType.CC, parse(a));
            for (String a : email.bcc()) msg.addRecipient(Message.RecipientType.BCC, parse(a));
            if (email.replyTo() != null) {
                msg.setReplyTo(new Address[]{parse(email.replyTo())});
            }
            msg.setSubject(email.subject(), StandardCharsets.UTF_8.name());
            msg.setSentDate(new Date());

            if (email.attachments().isEmpty()) {
                setBody(msg, email);
            } else {
                MimeBodyPart body = new MimeBodyPart();
                setBody(body, email);
                MimeMultipart mixed = new MimeMultipart("mixed");
                mixed.addBodyPart(body);
                for (Email.Attachment att : email.attachments()) {
                    MimeBodyPart part = new MimeBodyPart();
                    part.setDataHandler(new DataHandler(new ByteArrayDataSource(att.content(), att.contentType())));
                    part.setFileName(att.fileName());
                    part.setDisposition(MimeBodyPart.ATTACHMENT);
                    mixed.addBodyPart(part);
                }
                msg.setContent(mixed);
            }
            msg.saveChanges();
            return msg;
        } catch (Exception e) {
            throw new MailException("Invalid email: " + e.getMessage(), e);
        }
    }

    /** The message body: text, html, or a multipart/alternative of both. */
    private static void setBody(MimePart part, Email email) throws MessagingException {
        if (email.text() != null && email.html() != null) {
            MimeMultipart alt = new MimeMultipart("alternative");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(email.text(), StandardCharsets.UTF_8.name());
            MimeBodyPart html = new MimeBodyPart();
            html.setText(email.html(), StandardCharsets.UTF_8.name(), "html");
            alt.addBodyPart(text);
            alt.addBodyPart(html);
            part.setContent(alt);
        } else if (email.html() != null) {
            part.setText(email.html(), StandardCharsets.UTF_8.name(), "html");
        } else {
            part.setText(email.text(), StandardCharsets.UTF_8.name());
        }
    }

    private static InternetAddress parse(String address) throws AddressException {
        InternetAddress a = new InternetAddress(address, true);
        a.validate();
        return a;
    }
}
