package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.internal.Masking;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link MailTransport#precheck}: a cheap local check, before any network call, of whether the
 * transport can carry an email (total size, recipient counts, header rules). A rejected transport is skipped, and
 * its reason is listed in {@code NoEligibleTransportException} if no other transport can carry the email.
 *
 * <p>Addresses in a reason are masked, because reasons end up in exception messages and logs.
 *
 * @since 0.1.0
 */
public final class Precheck {

    private static final Precheck OK = new Precheck(null);

    private final String reason;

    private Precheck(String reason) {
        this.reason = reason;
    }

    /**
     * The transport can carry the email.
     *
     * @return the passing result
     * @since 0.1.0
     */
    public static Precheck ok() {
        return OK;
    }

    /**
     * The transport can't carry the email.
     *
     * @param reason why, such as {@code total size 52 MB is over the 50 MB limit}
     * @return the rejecting result
     * @throws IllegalArgumentException if the reason is blank
     * @since 0.1.0
     */
    public static Precheck reject(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.trim().isEmpty()) {
            throw new IllegalArgumentException("a precheck rejection needs a reason");
        }
        return new Precheck(Masking.addressesIn(reason.trim()));
    }

    /**
     * Tells whether the transport can carry the email.
     *
     * @return true if it passed
     * @since 0.1.0
     */
    public boolean passed() {
        return reason == null;
    }

    /**
     * Returns why the transport can't carry the email.
     *
     * @return the reason, with addresses masked; empty if it passed
     * @since 0.1.0
     */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Two results are equal when both passed, or both were rejected for the same reason.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Precheck && Objects.equals(reason, ((Precheck) other).reason));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reason);
    }

    /**
     * Describes the result, such as {@code Precheck[ok]} or {@code Precheck[rejected: too large]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return reason == null ? "Precheck[ok]" : "Precheck[rejected: " + reason + "]";
    }
}
