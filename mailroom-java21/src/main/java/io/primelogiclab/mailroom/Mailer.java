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
 */
public class Mailer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Mailer.class.getName());

    private final MailConfig config;
    private final Session session;
    private final AtomicInteger connectionsOpened = new AtomicInteger();
    // A lock rather than synchronized: on Java 21 a virtual thread blocked on SMTP I/O inside
    // synchronized pins its carrier thread; with ReentrantLock it unmounts.
    private final ReentrantLock lock = new ReentrantLock();
    private Transport transport;

    public Mailer(MailConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.session = Session.getInstance(config.toProperties());
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
                transport.sendMessage(message, message.getAllRecipients());
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
                    LOG.log(Level.WARNING, "Attempt {0}/{1} to {2} failed: {3}; retrying",
                            new Object[]{attempt, max, what, e.getMessage()});
                    pause();
                }
            }
        }

        MessagingException last = failures.get(failures.size() - 1);
        MailException ex = new MailException(
                "Failed to " + what + " after " + failures.size() + " attempt(s): " + last.getMessage(), last);
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
        disconnect();
        Transport t = session.getTransport(config.protocol());
        if (config.hasCredentials()) {
            t.connect(config.host(), config.port(), config.username(), config.password());
        } else {
            t.connect(config.host(), config.port(), null, null);
        }
        transport = t;
        connectionsOpened.incrementAndGet();
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
