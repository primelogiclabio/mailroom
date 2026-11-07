package io.primelogiclab.mailroom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable email message. Create with {@link #builder()}.
 *
 * <pre>{@code
 * Email email = Email.builder()
 *         .from("noreply@example.com")
 *         .to("alice@example.com")
 *         .subject("Hello")
 *         .text("Plain text body")
 *         .html("<p>HTML body</p>")
 *         .build();
 * }</pre>
 */
public final class Email {

    private final String from;
    private final List<String> to;
    private final List<String> cc;
    private final List<String> bcc;
    private final String replyTo;
    private final String subject;
    private final String text;
    private final String html;
    private final List<Attachment> attachments;

    private Email(Builder b) {
        this.from = b.from;
        this.to = copy(b.to);
        this.cc = copy(b.cc);
        this.bcc = copy(b.bcc);
        this.replyTo = b.replyTo;
        this.subject = b.subject;
        this.text = b.text;
        this.html = b.html;
        this.attachments = copy(b.attachments);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String from() { return from; }
    public List<String> to() { return to; }
    public List<String> cc() { return cc; }
    public List<String> bcc() { return bcc; }
    public String replyTo() { return replyTo; }
    public String subject() { return subject; }
    public String text() { return text; }
    public String html() { return html; }
    public List<Attachment> attachments() { return attachments; }

    private static <T> List<T> copy(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** A file attached to an email. */
    public static final class Attachment {
        private final String fileName;
        private final byte[] content;
        private final String contentType;

        public Attachment(String fileName, byte[] content, String contentType) {
            this.fileName = Objects.requireNonNull(fileName, "fileName");
            this.content = Objects.requireNonNull(content, "content");
            this.contentType = Objects.requireNonNull(contentType, "contentType");
        }

        public String fileName() { return fileName; }
        public byte[] content() { return content; }
        public String contentType() { return contentType; }
    }

    public static final class Builder {
        private String from;
        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> bcc = new ArrayList<>();
        private String replyTo;
        private String subject = "";
        private String text;
        private String html;
        private final List<Attachment> attachments = new ArrayList<>();

        private Builder() {
        }

        /** Sender address, e.g. {@code "noreply@example.com"} or {@code "App <noreply@example.com>"}. */
        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder to(String... addresses) {
            to.addAll(Arrays.asList(addresses));
            return this;
        }

        public Builder to(List<String> addresses) {
            to.addAll(addresses);
            return this;
        }

        public Builder cc(String... addresses) {
            cc.addAll(Arrays.asList(addresses));
            return this;
        }

        public Builder bcc(String... addresses) {
            bcc.addAll(Arrays.asList(addresses));
            return this;
        }

        public Builder replyTo(String address) {
            this.replyTo = address;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject != null ? subject : "";
            return this;
        }

        /** Plain-text body. If {@link #html(String)} is also set, both are sent as alternatives. */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** HTML body. */
        public Builder html(String html) {
            this.html = html;
            return this;
        }

        public Builder attach(String fileName, byte[] content, String contentType) {
            attachments.add(new Attachment(fileName, content.clone(), contentType));
            return this;
        }

        /** Attaches a file from disk; the content type is guessed from the file, falling back to octet-stream. */
        public Builder attach(Path file) {
            try {
                String type = Files.probeContentType(file);
                return attach(file.getFileName().toString(), Files.readAllBytes(file),
                        type != null ? type : "application/octet-stream");
            } catch (IOException e) {
                throw new MailException("Could not read attachment " + file, e);
            }
        }

        public Email build() {
            if (from == null || from.trim().isEmpty()) {
                throw new IllegalStateException("from is required");
            }
            if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
                throw new IllegalStateException("at least one recipient (to, cc or bcc) is required");
            }
            if (text == null && html == null) {
                throw new IllegalStateException("a text or html body is required");
            }
            return new Email(this);
        }
    }
}
