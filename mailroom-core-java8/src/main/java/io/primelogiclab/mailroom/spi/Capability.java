package io.primelogiclab.mailroom.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A feature a transport can offer, such as sending HTML or honouring a scheduled send time. Capabilities are an open
 * set of string keys rather than an enum, so a provider module can introduce a new one without a core release. Core
 * defines the well-known ones as constants; a provider's own capabilities should be namespaced, such as
 * {@code sendgrid.ip-pools}.
 *
 * <p>Keys are lower-case ASCII letters and digits, separated by single {@code .}, {@code _} or {@code -} characters,
 * at most 64 characters long. Two capabilities with the same key are equal.
 *
 * @since 0.1.0
 */
public final class Capability {

    private static final int MAX_KEY = 64;
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");

    /**
     * Sends an HTML body.
     *
     * @since 0.1.0
     */
    public static final Capability HTML = new Capability("html");

    /**
     * Sends a plain-text body.
     *
     * @since 0.1.0
     */
    public static final Capability TEXT = new Capability("text");

    /**
     * Sends regular attachments.
     *
     * @since 0.1.0
     */
    public static final Capability ATTACHMENTS = new Capability("attachments");

    /**
     * Sends inline parts that the HTML body shows through {@code cid:} links.
     *
     * @since 0.1.0
     */
    public static final Capability INLINE_IMAGES = new Capability("inline-images");

    /**
     * Sends arbitrary custom headers.
     *
     * @since 0.1.0
     */
    public static final Capability CUSTOM_HEADERS = new Capability("custom-headers");

    /**
     * Maps the email's tags to a native provider feature.
     *
     * @since 0.1.0
     */
    public static final Capability TAGS = new Capability("tags");

    /**
     * Maps the email's metadata to a native provider feature.
     *
     * @since 0.1.0
     */
    public static final Capability METADATA = new Capability("metadata");

    /**
     * Honours the email's {@code sendAt} time.
     *
     * @since 0.1.0
     */
    public static final Capability SCHEDULING = new Capability("scheduling");

    /**
     * Dedupes on the email's idempotency key.
     *
     * @since 0.1.0
     */
    public static final Capability IDEMPOTENCY = new Capability("idempotency");

    /**
     * Has a native endpoint that takes several messages at once.
     *
     * @since 0.1.0
     */
    public static final Capability BATCH = new Capability("batch");

    /**
     * Returns a message id on success.
     *
     * @since 0.1.0
     */
    public static final Capability MESSAGE_ID = new Capability("message-id");

    private final String key;

    private Capability(String key) {
        this.key = key;
    }

    /**
     * Returns the capability with this key. For a well-known key the result equals the constant.
     *
     * @param key the key, such as {@code html} or {@code sendgrid.ip-pools}
     * @return the capability
     * @throws IllegalArgumentException if the key is not lower-case letters and digits separated by {@code .},
     *                                  {@code _} or {@code -}, or is longer than 64 characters
     * @since 0.1.0
     */
    public static Capability of(String key) {
        Objects.requireNonNull(key, "key");
        if (key.length() > MAX_KEY || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("A capability key must be 1 to " + MAX_KEY
                    + " lower-case letters and digits separated by '.', '_' or '-'");
        }
        return new Capability(key);
    }

    /**
     * Returns the key.
     *
     * @return the key, such as {@code inline-images}
     * @since 0.1.0
     */
    public String key() {
        return key;
    }

    /**
     * Two capabilities are equal when their keys are equal.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Capability && key.equals(((Capability) other).key));
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    /**
     * Returns the key.
     *
     * @return the key
     */
    @Override
    public String toString() {
        return key;
    }
}
