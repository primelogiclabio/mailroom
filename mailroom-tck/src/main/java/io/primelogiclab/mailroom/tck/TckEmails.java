package io.primelogiclab.mailroom.tck;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The canonical emails the contracts send, by name. Each one exercises one part of the model, and
 * {@code "everything"} combines them. All addresses are under {@code tck.example.com}.
 *
 * <p>{@link #needs(Email)} tells which capabilities an email requires, so a contract skips the emails a transport
 * doesn't claim to carry.
 *
 * @since 0.1.0
 */
public final class TckEmails {

    /**
     * The sender of every canonical email.
     *
     * @since 0.1.0
     */
    public static final Address SENDER = Address.of("TCK Sender", "sender@tck.example.com");

    /** A PNG signature and IHDR start: enough to look like an image. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D',
        'R'};

    private TckEmails() {
    }

    /**
     * Returns every canonical email, by name, in a fixed order.
     *
     * @return the emails; the map cannot be changed
     * @since 0.1.0
     */
    public static Map<String, Email> all() {
        Map<String, Email> emails = new LinkedHashMap<>();
        emails.put("minimal text", minimal());
        emails.put("text and html", base("TCK text and html").text("Plain part").html("<p>HTML <b>part</b></p>")
                .build());
        emails.put("html only", base("TCK html only").html("<p>Only HTML</p>").build());
        emails.put("recipients", base("TCK recipients").text("Recipients")
                .to(Address.of("To Two", "to.two@tck.example.com"))
                .cc(Address.of("Cc One", "cc.one@tck.example.com"))
                .bcc("bcc.one@tck.example.com")
                .replyTo(Address.of("Reply Here", "reply@tck.example.com"))
                .build());
        emails.put("unicode", Email.builder().from(Address.of("Zoë Ünïcode", "sender@tck.example.com"))
                .to(Address.of("Jürgen 日本", "to.one@tck.example.com"))
                .subject("Grüße 👋 — 日本語のテスト").text("Ünïcode bödy: 中文, emoji 😀, accents é è ê.")
                .html("<p>Ünïcode bödy: 中文 😀</p>").build());
        emails.put("attachments", base("TCK attachments").text("Two attachments")
                .attach(Attachment.of("bytes.bin", "application/octet-stream", allBytes()))
                .attach(Attachment.of("notes.txt", "text/plain",
                        "line one\nline two\n".getBytes(StandardCharsets.UTF_8)))
                .build());
        emails.put("inline image", base("TCK inline image").text("See the logo")
                .html("<p><img src=\"cid:logo\" alt=\"logo\"></p>")
                .attach(Attachment.inline("logo", "logo.png", "image/png", PNG.clone()))
                .build());
        emails.put("custom headers", base("TCK custom headers").text("Headers")
                .header("X-Tck-Trace", "trace-123").header("X-Tck-Campaign", "autumn").build());
        emails.put("tags", base("TCK tags").text("Tags").tag("tck", "welcome").build());
        emails.put("metadata", base("TCK metadata").text("Metadata").metadata("order_id", "A-1042")
                .metadata("tenant", "tck").build());
        emails.put("everything", everything());
        return Collections.unmodifiableMap(emails);
    }

    /**
     * Returns the smallest canonical email: one recipient, a subject and a text body. The failure contracts send it.
     *
     * @return the email
     * @since 0.1.0
     */
    public static Email minimal() {
        return base("TCK minimal").text("Hello from the mailroom TCK.").build();
    }

    /**
     * Returns the capabilities a transport needs to carry an email: {@code TEXT}, {@code HTML}, {@code ATTACHMENTS},
     * {@code INLINE_IMAGES}, {@code CUSTOM_HEADERS}, {@code TAGS} and {@code METADATA}, as the email uses them.
     *
     * @param email the email
     * @return the capabilities
     * @since 0.1.0
     */
    public static Set<Capability> needs(Email email) {
        Set<Capability> needs = new HashSet<>();
        if (email.text().isPresent()) {
            needs.add(Capability.TEXT);
        }
        if (email.html().isPresent()) {
            needs.add(Capability.HTML);
        }
        for (Attachment attachment : email.attachments()) {
            needs.add(attachment.isInline() ? Capability.INLINE_IMAGES : Capability.ATTACHMENTS);
        }
        if (!email.headers().isEmpty()) {
            needs.add(Capability.CUSTOM_HEADERS);
        }
        if (!email.tags().isEmpty()) {
            needs.add(Capability.TAGS);
        }
        if (!email.metadata().isEmpty()) {
            needs.add(Capability.METADATA);
        }
        return needs;
    }

    /**
     * Tells whether a transport claims every capability an email needs.
     *
     * @param capabilities the transport's capabilities
     * @param email        the email
     * @return true if the transport can carry it
     * @since 0.1.0
     */
    public static boolean carries(TransportCapabilities capabilities, Email email) {
        return capabilities.supported().containsAll(needs(email));
    }

    private static Email.Builder base(String subject) {
        return Email.builder().from(SENDER).to(Address.of("To One", "to.one@tck.example.com")).subject(subject);
    }

    private static Email everything() {
        return base("TCK everything").text("Everything, plain").html("<p>Everything <img src=\"cid:logo\"></p>")
                .to("to.three@tck.example.com").cc("cc.one@tck.example.com").bcc("bcc.one@tck.example.com")
                .replyTo("reply@tck.example.com")
                .attach(Attachment.of("bytes.bin", "application/octet-stream", allBytes()))
                .attach(Attachment.inline("logo", "logo.png", "image/png", PNG.clone()))
                .header("X-Tck-Trace", "trace-456").tag("tck", "everything").metadata("order_id", "B-2048")
                .build();
    }

    private static byte[] allBytes() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
