package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.Chars;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An email to send: sender, recipients, subject, bodies, attachments and the extras some providers use.
 *
 * <p>An {@code Email} is immutable and is checked when it is built, so a bad message fails in your code, not halfway
 * through a failover chain:
 *
 * <pre>{@code
 * Email email = Email.builder()
 *     .from("PrimeLogicLab <no-reply@primelogiclab.io>")
 *     .to("player@example.com")
 *     .subject("Your tee time is confirmed")
 *     .text(textBody)
 *     .html(htmlBody)
 *     .attach(Attachment.of("scorecard.pdf", "application/pdf", pdfBytes))
 *     .tag("booking")
 *     .metadata("bookingId", "B-1042")
 *     .idempotencyKey("booking-confirmed/B-1042")
 *     .build();                       // throws InvalidEmailException if anything is wrong
 * }</pre>
 *
 * <p>The rules {@link Builder#build()} checks:
 * <ul>
 *   <li>at least one recipient across {@code to}, {@code cc} and {@code bcc};</li>
 *   <li>a subject that is not blank and has no line breaks or other control characters;</li>
 *   <li>a text body, an HTML body or both, neither of them empty;</li>
 *   <li>the subject and bodies may be left out when an {@linkplain EmailExtension#providesContent() extension
 *       supplies the content}, such as a provider template; any that are given are still checked;</li>
 *   <li>inline attachments with distinct content ids;</li>
 *   <li>custom headers whose names are printable ASCII without {@code :}, whose values have no line breaks or other
 *       control characters (tab is allowed), and which don't use a name the email sets itself: From, Sender,
 *       Reply-To, To, Cc, Bcc, Subject, Date, Message-ID, MIME-Version, Content-Type, Content-Transfer-Encoding,
 *       Content-ID, Content-Disposition or Return-Path;</li>
 *   <li>tags and metadata keys of 1 to 64 ASCII letters, digits, {@code _} or {@code -}, and metadata values without
 *       control characters;</li>
 *   <li>an idempotency key of 1 to 256 visible ASCII characters.</li>
 * </ul>
 * Addresses and attachments are checked as they are created ({@link Address}, {@link Attachment}).
 *
 * <p>Provider-specific options travel as {@link EmailExtension}s: attach them with {@link Builder#with} and read them
 * with {@link #extension(Class)}.
 *
 * <p>{@link #toString()} shows only counts, never addresses, the subject or the bodies.
 *
 * @since 0.1.0
 */
public final class Email {

    private static final int MAX_TOKEN = 64;
    private static final int MAX_IDEMPOTENCY_KEY = 256;
    private static final Set<String> RESERVED_HEADERS = new HashSet<>(Arrays.asList(
            "from", "sender", "reply-to", "to", "cc", "bcc", "subject", "date", "message-id", "mime-version",
            "content-type", "content-transfer-encoding", "content-id", "content-disposition", "return-path"));

    private final Address from;
    private final List<Address> replyTo;
    private final List<Address> to;
    private final List<Address> cc;
    private final List<Address> bcc;
    private final List<Address> recipients;
    private final String subject;
    private final String text;
    private final String html;
    private final List<Attachment> attachments;
    private final Map<String, String> headers;
    private final Set<String> tags;
    private final Map<String, String> metadata;
    private final Instant sendAt;
    private final String idempotencyKey;
    private final Map<Class<? extends EmailExtension>, EmailExtension> extensions;

    private Email(Builder b) {
        this.from = b.from;
        this.replyTo = copy(b.replyTo);
        this.to = copy(b.to);
        this.cc = copy(b.cc);
        this.bcc = copy(b.bcc);
        List<Address> all = new ArrayList<>(to);
        all.addAll(cc);
        all.addAll(bcc);
        this.recipients = Collections.unmodifiableList(all);
        this.subject = b.subject;
        this.text = b.text;
        this.html = b.html;
        this.attachments = copy(b.attachments);
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(b.tags));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
        this.sendAt = b.sendAt;
        this.idempotencyKey = b.idempotencyKey;
        this.extensions = Collections.unmodifiableMap(
                new LinkedHashMap<>(b.extensions));
    }

    /**
     * Starts a new email.
     *
     * @return an empty builder
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a builder holding this email's values, for a changed copy.
     *
     * @return a builder with every field of this email
     * @since 0.1.0
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.from = from;
        b.replyTo.addAll(replyTo);
        b.to.addAll(to);
        b.cc.addAll(cc);
        b.bcc.addAll(bcc);
        b.subject = subject;
        b.text = text;
        b.html = html;
        b.attachments.addAll(attachments);
        b.headers.putAll(headers);
        b.tags.addAll(tags);
        b.metadata.putAll(metadata);
        b.sendAt = sendAt;
        b.idempotencyKey = idempotencyKey;
        b.extensions.putAll(extensions);
        return b;
    }

    /**
     * Returns the sender. When it is empty, the {@code Mailroom}'s configured default sender is used.
     *
     * @return the sender, if set
     * @since 0.1.0
     */
    public Optional<Address> from() {
        return Optional.ofNullable(from);
    }

    /**
     * Returns the addresses replies should go to.
     *
     * @return the reply-to addresses, possibly empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Address> replyTo() {
        return replyTo;
    }

    /**
     * Returns the {@code To} recipients.
     *
     * @return the addresses, possibly empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Address> to() {
        return to;
    }

    /**
     * Returns the {@code Cc} recipients.
     *
     * @return the addresses, possibly empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Address> cc() {
        return cc;
    }

    /**
     * Returns the {@code Bcc} recipients.
     *
     * @return the addresses, possibly empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Address> bcc() {
        return bcc;
    }

    /**
     * Returns every recipient: {@code to}, then {@code cc}, then {@code bcc}.
     *
     * @return the addresses, never empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Address> recipients() {
        return recipients;
    }

    /**
     * Returns the subject. It is absent only when an extension supplies the content.
     *
     * @return the subject
     * @since 0.1.0
     */
    public Optional<String> subject() {
        return Optional.ofNullable(subject);
    }

    /**
     * Returns the plain-text body.
     *
     * @return the text body, if set
     * @since 0.1.0
     */
    public Optional<String> text() {
        return Optional.ofNullable(text);
    }

    /**
     * Returns the HTML body.
     *
     * @return the HTML body, if set
     * @since 0.1.0
     */
    public Optional<String> html() {
        return Optional.ofNullable(html);
    }

    /**
     * Returns the attachments and inline parts, in the order they were added.
     *
     * @return the attachments, possibly empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Attachment> attachments() {
        return attachments;
    }

    /**
     * Returns the custom headers, in the order they were added, with names as given.
     *
     * @return the headers, possibly empty; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Returns the tags, short labels for grouping and reporting, in the order they were added.
     *
     * @return the tags, possibly empty; the set cannot be changed
     * @since 0.1.0
     */
    public Set<String> tags() {
        return tags;
    }

    /**
     * Returns the metadata, key/value pairs that providers echo back in webhooks where they support it. Don't put
     * personal data here: providers store it.
     *
     * @return the metadata, possibly empty; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Returns when the provider should send the email. Only transports that can schedule are used for it.
     *
     * @return the scheduled time, if set
     * @since 0.1.0
     */
    public Optional<Instant> sendAt() {
        return Optional.ofNullable(sendAt);
    }

    /**
     * Returns the key that identifies this email as one business event, such as {@code booking-confirmed/B-1042}.
     * Providers that dedupe on it won't send the same key twice.
     *
     * @return the idempotency key, if set
     * @since 0.1.0
     */
    public Optional<String> idempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    /**
     * Returns the extension of exactly this class, if the email carries one.
     *
     * @param type the extension class, such as {@code SendGridOptions.class}
     * @param <T>  the extension type
     * @return the extension, if present
     * @since 0.1.0
     */
    public <T extends EmailExtension> Optional<T> extension(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return Optional.ofNullable(type.cast(extensions.get(type)));
    }

    /**
     * Returns every extension, in the order they were added.
     *
     * @return the extensions, possibly empty; the collection cannot be changed
     * @since 0.1.0
     */
    public Collection<EmailExtension> extensions() {
        return extensions.values();
    }

    /**
     * Two emails are equal when every field is equal, attachments byte by byte.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Email that)) {
            return false;
        }
        return Objects.equals(from, that.from) && replyTo.equals(that.replyTo) && to.equals(that.to)
                && cc.equals(that.cc) && bcc.equals(that.bcc) && Objects.equals(subject, that.subject)
                && Objects.equals(text, that.text) && Objects.equals(html, that.html)
                && attachments.equals(that.attachments) && headers.equals(that.headers) && tags.equals(that.tags)
                && metadata.equals(that.metadata) && Objects.equals(sendAt, that.sendAt)
                && Objects.equals(idempotencyKey, that.idempotencyKey) && extensions.equals(that.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, replyTo, to, cc, bcc, subject, text, html, attachments, headers, tags, metadata,
                sendAt, idempotencyKey, extensions);
    }

    /**
     * Describes the email by counts only, such as
     * {@code Email[to=1, cc=0, bcc=0, attachments=1, tags=1, extensions=0]}, so it is safe to log.
     *
     * @return a description without addresses, subject or bodies
     */
    @Override
    public String toString() {
        return "Email[to=" + to.size() + ", cc=" + cc.size() + ", bcc=" + bcc.size() + ", attachments="
                + attachments.size() + ", tags=" + tags.size() + ", extensions=" + extensions.size() + "]";
    }

    private static <T> List<T> copy(List<T> list) {
        return List.copyOf(list);
    }

    /**
     * Collects an email's fields; {@link #build()} checks them and returns the {@link Email}.
     *
     * <p>Addresses given as text are parsed when they are added, so a malformed one fails at that call. Every other
     * rule is checked by {@link #build()}, which reports all problems at once. A builder can be reused after
     * {@code build()}; emails already built don't change. Builders are not thread-safe.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private Address from;
        private final List<Address> replyTo = new ArrayList<>();
        private final List<Address> to = new ArrayList<>();
        private final List<Address> cc = new ArrayList<>();
        private final List<Address> bcc = new ArrayList<>();
        private String subject;
        private String text;
        private String html;
        private final List<Attachment> attachments = new ArrayList<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private Instant sendAt;
        private String idempotencyKey;
        private final Map<Class<? extends EmailExtension>, EmailExtension> extensions =
                new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the sender.
         *
         * @param address the sender, such as {@code PrimeLogicLab <no-reply@primelogiclab.io>}
         * @return this builder
         * @throws InvalidEmailException if the address is not valid
         * @since 0.1.0
         */
        public Builder from(String address) {
            this.from = Address.parse(Objects.requireNonNull(address, "from"));
            return this;
        }

        /**
         * Sets the sender.
         *
         * @param address the sender
         * @return this builder
         * @since 0.1.0
         */
        public Builder from(Address address) {
            this.from = Objects.requireNonNull(address, "from");
            return this;
        }

        /**
         * Adds addresses replies should go to.
         *
         * @param addresses the addresses, such as {@code support@example.com}
         * @return this builder
         * @throws InvalidEmailException if an address is not valid
         * @since 0.1.0
         */
        public Builder replyTo(String... addresses) {
            addParsed(replyTo, addresses, "replyTo");
            return this;
        }

        /**
         * Adds addresses replies should go to.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder replyTo(Address... addresses) {
            addAll(replyTo, Arrays.asList(Objects.requireNonNull(addresses, "replyTo")), "replyTo");
            return this;
        }

        /**
         * Adds {@code To} recipients.
         *
         * @param addresses the addresses, such as {@code player@example.com} or {@code Name <player@example.com>}
         * @return this builder
         * @throws InvalidEmailException if an address is not valid
         * @since 0.1.0
         */
        public Builder to(String... addresses) {
            addParsed(to, addresses, "to");
            return this;
        }

        /**
         * Adds {@code To} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder to(Address... addresses) {
            addAll(to, Arrays.asList(Objects.requireNonNull(addresses, "to")), "to");
            return this;
        }

        /**
         * Adds {@code To} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder to(Collection<? extends Address> addresses) {
            addAll(to, Objects.requireNonNull(addresses, "to"), "to");
            return this;
        }

        /**
         * Adds {@code Cc} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @throws InvalidEmailException if an address is not valid
         * @since 0.1.0
         */
        public Builder cc(String... addresses) {
            addParsed(cc, addresses, "cc");
            return this;
        }

        /**
         * Adds {@code Cc} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder cc(Address... addresses) {
            addAll(cc, Arrays.asList(Objects.requireNonNull(addresses, "cc")), "cc");
            return this;
        }

        /**
         * Adds {@code Cc} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder cc(Collection<? extends Address> addresses) {
            addAll(cc, Objects.requireNonNull(addresses, "cc"), "cc");
            return this;
        }

        /**
         * Adds {@code Bcc} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @throws InvalidEmailException if an address is not valid
         * @since 0.1.0
         */
        public Builder bcc(String... addresses) {
            addParsed(bcc, addresses, "bcc");
            return this;
        }

        /**
         * Adds {@code Bcc} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder bcc(Address... addresses) {
            addAll(bcc, Arrays.asList(Objects.requireNonNull(addresses, "bcc")), "bcc");
            return this;
        }

        /**
         * Adds {@code Bcc} recipients.
         *
         * @param addresses the addresses
         * @return this builder
         * @since 0.1.0
         */
        public Builder bcc(Collection<? extends Address> addresses) {
            addAll(bcc, Objects.requireNonNull(addresses, "bcc"), "bcc");
            return this;
        }

        /**
         * Sets the subject.
         *
         * @param subject the subject line, without line breaks
         * @return this builder
         * @since 0.1.0
         */
        public Builder subject(String subject) {
            this.subject = Objects.requireNonNull(subject, "subject");
            return this;
        }

        /**
         * Sets the plain-text body.
         *
         * @param text the body
         * @return this builder
         * @since 0.1.0
         */
        public Builder text(String text) {
            this.text = Objects.requireNonNull(text, "text");
            return this;
        }

        /**
         * Sets the HTML body. Render templates yourself; mailroom sends the HTML as given.
         *
         * @param html the body
         * @return this builder
         * @since 0.1.0
         */
        public Builder html(String html) {
            this.html = Objects.requireNonNull(html, "html");
            return this;
        }

        /**
         * Adds an attachment or an inline part.
         *
         * @param attachment the attachment
         * @return this builder
         * @since 0.1.0
         */
        public Builder attach(Attachment attachment) {
            attachments.add(Objects.requireNonNull(attachment, "attachment"));
            return this;
        }

        /**
         * Sets a custom header. Setting a name again, in any letter case, replaces the earlier value.
         *
         * @param name  the header name, such as {@code List-Unsubscribe}
         * @param value the header value, without line breaks
         * @return this builder
         * @since 0.1.0
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Iterator<String> names = headers.keySet().iterator();
            while (names.hasNext()) {
                if (names.next().equalsIgnoreCase(name)) {
                    names.remove();
                }
            }
            headers.put(name, value);
            return this;
        }

        /**
         * Adds tags. A tag added twice is kept once.
         *
         * @param tags the tags, each 1 to 64 ASCII letters, digits, {@code _} or {@code -}
         * @return this builder
         * @since 0.1.0
         */
        public Builder tag(String... tags) {
            for (String tag : Objects.requireNonNull(tags, "tags")) {
                this.tags.add(Objects.requireNonNull(tag, "tag"));
            }
            return this;
        }

        /**
         * Sets a metadata entry. Setting a key again replaces the earlier value.
         *
         * @param key   the key, 1 to 64 ASCII letters, digits, {@code _} or {@code -}
         * @param value the value, without control characters; not personal data
         * @return this builder
         * @since 0.1.0
         */
        public Builder metadata(String key, String value) {
            metadata.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Asks the provider to send the email at a later time.
         *
         * @param sendAt when to send
         * @return this builder
         * @since 0.1.0
         */
        public Builder sendAt(Instant sendAt) {
            this.sendAt = Objects.requireNonNull(sendAt, "sendAt");
            return this;
        }

        /**
         * Sets the key that identifies this email as one business event, such as {@code booking-confirmed/B-1042}.
         *
         * @param idempotencyKey 1 to 256 visible ASCII characters
         * @return this builder
         * @since 0.1.0
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            return this;
        }

        /**
         * Attaches provider-specific options. An email holds one extension per class: adding another instance of
         * the same class replaces the earlier one.
         *
         * @param extension the options, such as a provider module's options object
         * @return this builder
         * @since 0.1.0
         */
        public Builder with(EmailExtension extension) {
            Objects.requireNonNull(extension, "extension");
            extensions.remove(extension.getClass());
            extensions.put(extension.getClass(), extension);
            return this;
        }

        /**
         * Checks every rule and builds the email.
         *
         * @return the email
         * @throws InvalidEmailException listing every rule that failed
         * @since 0.1.0
         */
        public Email build() {
            List<String> problems = new ArrayList<>();
            if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
                problems.add("at least one recipient (to, cc or bcc) is required");
            }
            boolean contentSupplied = false;
            for (EmailExtension extension : extensions.values()) {
                contentSupplied |= extension.providesContent();
            }
            if (subject == null) {
                if (!contentSupplied) {
                    problems.add("a subject is required");
                }
            } else if (Chars.isBlank(subject)) {
                problems.add(contentSupplied ? "the subject must not be blank" : "a subject is required");
            } else if (Chars.hasControl(subject)) {
                problems.add("the subject must not contain line breaks or other control characters");
            }
            if (text == null && html == null && !contentSupplied) {
                problems.add("a text body, an HTML body or both are required");
            }
            if (text != null && text.isEmpty()) {
                problems.add("the text body must not be empty");
            }
            if (html != null && html.isEmpty()) {
                problems.add("the HTML body must not be empty");
            }
            checkAttachments(problems);
            checkHeaders(problems);
            checkTags(problems);
            checkMetadata(problems);
            if (idempotencyKey != null && (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY
                    || !Chars.isVisibleAscii(idempotencyKey))) {
                problems.add("the idempotency key must be 1 to " + MAX_IDEMPOTENCY_KEY
                        + " visible ASCII characters (no spaces)");
            }
            if (!problems.isEmpty()) {
                throw new InvalidEmailException(problems);
            }
            return new Email(this);
        }

        private void checkAttachments(List<String> problems) {
            Set<String> contentIds = new HashSet<>();
            for (Attachment attachment : attachments) {
                String id = attachment.contentId().orElse(null);
                if (id != null && !contentIds.add(id)) {
                    problems.add("the content id '" + id + "' is used by more than one inline attachment");
                }
            }
        }

        private void checkHeaders(List<String> problems) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey();
                if (!isHeaderName(name)) {
                    problems.add("a header name must be printable ASCII without ':' or spaces");
                    continue;
                }
                if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    problems.add("the header '" + name + "' is set by the email itself; use its own field");
                }
                if (Chars.hasControlExceptTab(header.getValue())) {
                    problems.add("the value of header '" + name
                            + "' must not contain line breaks or other control characters");
                }
            }
        }

        private void checkTags(List<String> problems) {
            int position = 0;
            for (String tag : tags) {
                position++;
                if (!Chars.isToken(tag, MAX_TOKEN)) {
                    problems.add("tag " + position + " must be 1 to " + MAX_TOKEN
                            + " ASCII letters, digits, '_' or '-'");
                }
            }
        }

        private void checkMetadata(List<String> problems) {
            int position = 0;
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                position++;
                if (!Chars.isToken(entry.getKey(), MAX_TOKEN)) {
                    problems.add("metadata key " + position + " must be 1 to " + MAX_TOKEN
                            + " ASCII letters, digits, '_' or '-'");
                } else if (Chars.hasControl(entry.getValue())) {
                    problems.add("the metadata value for '" + entry.getKey()
                            + "' must not contain line breaks or other control characters");
                }
            }
        }

        private static boolean isHeaderName(String name) {
            if (name.isEmpty()) {
                return false;
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 0x21 || c > 0x7E || c == ':') {
                    return false;
                }
            }
            return true;
        }

        private static void addParsed(List<Address> target, String[] addresses, String field) {
            for (String address : Objects.requireNonNull(addresses, field)) {
                target.add(Address.parse(Objects.requireNonNull(address, field)));
            }
        }

        private static void addAll(List<Address> target, Collection<? extends Address> addresses, String field) {
            for (Address address : addresses) {
                target.add(Objects.requireNonNull(address, field));
            }
        }
    }
}
