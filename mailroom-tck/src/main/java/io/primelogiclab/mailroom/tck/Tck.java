package io.primelogiclab.mailroom.tck;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** What the contracts share: the send context, the field-by-field comparison, log capture and leak checks. */
final class Tck {

    private Tck() {
    }

    static SendContext context() {
        return SendContext.builder().build();
    }

    /** Reads a transport's capabilities from a throwaway instance, before any case primes anything. */
    static TransportCapabilities probe(MailTransport transport) {
        try {
            return transport.capabilities();
        } finally {
            transport.close();
        }
    }

    static String missing(TransportCapabilities capabilities, Email email) {
        Set<String> missing = new TreeSet<>();
        for (Capability capability : TckEmails.needs(email)) {
            if (!capabilities.supports(capability)) {
                missing.add(capability.key());
            }
        }
        return String.valueOf(missing);
    }

    static String join(List<String> lines) {
        StringBuilder s = new StringBuilder();
        for (String line : lines) {
            s.append("\n  - ").append(line);
        }
        return s.toString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mapping

    /** How the received email differs from the sent one, for the parts the transport claims to carry. */
    static List<String> differences(Email expected, Email actual, TransportCapabilities capabilities) {
        List<String> d = new ArrayList<>();
        address(d, "from", expected.from(), actual.from());
        addresses(d, "to", expected.to(), actual.to());
        addresses(d, "cc", expected.cc(), actual.cc());
        addresses(d, "bcc", expected.bcc(), actual.bcc());
        addresses(d, "reply-to", expected.replyTo(), actual.replyTo());
        if (!expected.subject().equals(actual.subject())) {
            d.add("subject: expected " + quote(expected.subject()) + " but was " + quote(actual.subject()));
        }
        if (capabilities.supports(Capability.TEXT)) {
            body(d, "text", expected.text(), actual.text());
        }
        if (capabilities.supports(Capability.HTML)) {
            body(d, "html", expected.html(), actual.html());
        }
        if (capabilities.supports(Capability.ATTACHMENTS)) {
            attachments(d, false, expected.attachments(), actual.attachments());
        }
        if (capabilities.supports(Capability.INLINE_IMAGES)) {
            attachments(d, true, expected.attachments(), actual.attachments());
        }
        if (capabilities.supports(Capability.CUSTOM_HEADERS)) {
            for (Map.Entry<String, String> header : expected.headers().entrySet()) {
                Optional<String> value = header(actual, header.getKey());
                if (!value.isPresent()) {
                    d.add("header " + header.getKey() + ": missing");
                } else if (!value.get().equals(header.getValue())) {
                    d.add("header " + header.getKey() + ": expected " + quote(header.getValue()) + " but was "
                            + quote(value.get()));
                }
            }
        }
        if (capabilities.supports(Capability.TAGS) && !expected.tags().equals(actual.tags())) {
            d.add("tags: expected " + new TreeSet<>(expected.tags()) + " but was "
                    + new TreeSet<>(actual.tags()));
        }
        if (capabilities.supports(Capability.METADATA) && !expected.metadata().equals(actual.metadata())) {
            d.add("metadata: expected " + expected.metadata() + " but was " + actual.metadata());
        }
        return d;
    }

    private static void address(List<String> d, String field, Optional<Address> expected, Optional<Address> actual) {
        if (!expected.isPresent()) {
            return;
        }
        if (!actual.isPresent()) {
            d.add(field + ": missing");
        } else if (!key(expected.get()).equals(key(actual.get()))) {
            d.add(field + ": expected " + expected.get().format() + " but was " + actual.get().format());
        }
    }

    /** Compared as sets: providers may reorder recipients. */
    private static void addresses(List<String> d, String field, List<Address> expected, List<Address> actual) {
        List<String> e = keys(expected);
        List<String> a = keys(actual);
        if (!e.equals(a)) {
            d.add(field + ": expected " + e + " but was " + a);
        }
    }

    private static List<String> keys(List<Address> addresses) {
        List<String> keys = new ArrayList<>();
        for (Address address : addresses) {
            keys.add(key(address));
        }
        Collections.sort(keys);
        return keys;
    }

    private static String key(Address address) {
        return (address.displayName().isPresent() ? address.displayName().get() + " " : "") + "<"
                + address.address().toLowerCase(Locale.ROOT) + ">";
    }

    /** Line endings and trailing newlines are the wire's business (SMTP sends CRLF), so they don't count. */
    private static void body(List<String> d, String field, Optional<String> expected, Optional<String> actual) {
        if (!expected.isPresent()) {
            return;
        }
        if (!actual.isPresent()) {
            d.add(field + ": missing");
        } else if (!normalize(expected.get()).equals(normalize(actual.get()))) {
            d.add(field + ": expected " + quote(expected) + " but was " + quote(actual));
        }
    }

    private static String normalize(String body) {
        String s = body.replace("\r\n", "\n");
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static void attachments(List<String> d, boolean inline, List<Attachment> expected,
            List<Attachment> actual) {
        List<Attachment> sent = only(expected, inline);
        List<Attachment> received = new ArrayList<>(only(actual, inline));
        String kind = inline ? "inline part " : "attachment ";
        for (Attachment e : sent) {
            String id = id(e, inline);
            Attachment match = null;
            for (Attachment a : received) {
                if (id.equals(id(a, inline))) {
                    match = a;
                    break;
                }
            }
            if (match == null) {
                d.add(kind + id + ": missing");
                continue;
            }
            received.remove(match);
            if (!mediaType(e.contentType()).equals(mediaType(match.contentType()))) {
                d.add(kind + id + ": content type expected " + mediaType(e.contentType()) + " but was "
                        + mediaType(match.contentType()));
            }
            byte[] eb = e.content();
            byte[] ab = match.content();
            if (!Arrays.equals(eb, ab)) {
                d.add(kind + id + ": " + bytesDiffer(eb, ab));
            }
        }
        for (Attachment extra : received) {
            d.add(kind + id(extra, inline) + ": not in the email sent");
        }
    }

    private static List<Attachment> only(List<Attachment> attachments, boolean inline) {
        List<Attachment> only = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (attachment.isInline() == inline) {
                only.add(attachment);
            }
        }
        return only;
    }

    private static String id(Attachment attachment, boolean inline) {
        if (inline) {
            String cid = attachment.contentId().orElse("?");
            return cid.startsWith("<") && cid.endsWith(">") ? cid.substring(1, cid.length() - 1) : cid;
        }
        return attachment.filename().orElse("(no filename)");
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static String bytesDiffer(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return expected.length + " bytes sent but " + actual.length + " received";
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                return "content differs from byte " + i;
            }
        }
        return "content differs";
    }

    private static Optional<String> header(Email email, String name) {
        for (Map.Entry<String, String> header : email.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return Optional.of(header.getValue());
            }
        }
        return Optional.empty();
    }

    private static String quote(Optional<String> value) {
        return value.isPresent() ? quote(value.get()) : "(none)";
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Redaction

    /** Where a forbidden string turned up, or null; a null secret checks addresses only. */
    static String leak(String where, String text, String secret, List<String> addresses) {
        if (text == null) {
            return null;
        }
        if (secret != null && text.contains(secret)) {
            return where + " shows the secret";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String address : addresses) {
            if (lower.contains(address.toLowerCase(Locale.ROOT))) {
                return where + " shows the full address " + address + "; mask it (Address.toString() does)";
            }
        }
        return null;
    }

    /** Leaks in a throwable's messages and toString, along its causes and suppressed exceptions. */
    static List<String> leaks(Throwable thrown, String where, String secret, List<String> addresses) {
        List<String> found = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        collect(thrown, where, secret, addresses, found, seen);
        return found;
    }

    private static void collect(Throwable t, String where, String secret, List<String> addresses, List<String> found,
            Map<Throwable, Boolean> seen) {
        if (t == null || seen.put(t, Boolean.TRUE) != null) {
            return;
        }
        String name = where + " (" + t.getClass().getName() + ")";
        add(found, leak(name + " getMessage()", t.getMessage(), secret, addresses));
        add(found, leak(name + " toString()", t.toString(), secret, addresses));
        collect(t.getCause(), where + "'s cause", secret, addresses, found, seen);
        for (Throwable suppressed : t.getSuppressed()) {
            collect(suppressed, where + "'s suppressed exception", secret, addresses, found, seen);
        }
    }

    static List<String> leaks(LogCapture logs, String secret, List<String> addresses, Set<String> ignoredLoggers) {
        List<String> found = new ArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        for (LogRecord record : logs.records()) {
            if (ignored(record.getLoggerName(), ignoredLoggers)) {
                continue;
            }
            String where = "a " + record.getLevel() + " log record from " + record.getLoggerName();
            add(found, leak(where, record.getMessage(), secret, addresses));
            add(found, leak(where + ", formatted", formatter.formatMessage(record), secret, addresses));
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    add(found, leak(where + ", a parameter", String.valueOf(parameter), secret, addresses));
                }
            }
            if (record.getThrown() != null) {
                found.addAll(leaks(record.getThrown(), where + ", its Throwable", secret, addresses));
            }
        }
        return found;
    }

    private static boolean ignored(String logger, Set<String> prefixes) {
        if (logger == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (logger.equals(prefix) || logger.startsWith(prefix.endsWith(".") ? prefix : prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<String> found, String leak) {
        if (leak != null) {
            found.add(leak);
        }
    }

    /** Every JUL record at every level while it runs, through the root logger. */
    static final class LogCapture extends Handler {

        private final Logger root = Logger.getLogger("");
        private final Level previous;
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        private LogCapture() {
            previous = root.getLevel();
            setLevel(Level.ALL);
            root.setLevel(Level.ALL);
            root.addHandler(this);
        }

        static LogCapture start() {
            return new LogCapture();
        }

        List<LogRecord> records() {
            return records;
        }

        void stop() {
            root.removeHandler(this);
            root.setLevel(previous);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
