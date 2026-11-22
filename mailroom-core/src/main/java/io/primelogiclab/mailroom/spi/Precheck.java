package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.internal.Masking;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link MailTransport#precheck}: a cheap local check, before any network call, of whether the
 * transport can carry an email (total size, recipient counts, header rules). A rejected transport is skipped, and
 * its reason is listed in {@code NoEligibleTransportException} if no other transport can carry the email.
 *
 * <p>Addresses in a reason are masked, because reasons end up in exception messages and logs. Create one with
 * {@link #ok()} or {@link #reject(String)}.
 *
 * @param reason why the transport can't carry the email, with addresses masked; empty when it can
 * @since 0.1.0
 */
public record Precheck(Optional<String> reason) {

    private static final Precheck OK = new Precheck(Optional.empty());

    /**
     * Trims the reason and masks the addresses in it.
     *
     * @throws IllegalArgumentException if the reason is blank
     * @since 0.1.0
     */
    public Precheck {
        Objects.requireNonNull(reason, "reason");
        if (reason.isPresent()) {
            var text = reason.get().trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("a precheck rejection needs a reason");
            }
            reason = Optional.of(Masking.addressesIn(text));
        }
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
        return new Precheck(Optional.of(Objects.requireNonNull(reason, "reason")));
    }

    /**
     * Tells whether the transport can carry the email.
     *
     * @return true for {@link #ok()}
     * @since 0.1.0
     */
    public boolean passed() {
        return reason.isEmpty();
    }

    /**
     * Describes the outcome, such as {@code Precheck[rejected: too large]}.
     *
     * @return a description, with addresses masked
     */
    @Override
    public String toString() {
        return reason.map(r -> "Precheck[rejected: " + r + "]").orElse("Precheck[ok]");
    }
}
