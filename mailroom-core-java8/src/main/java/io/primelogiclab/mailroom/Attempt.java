package io.primelogiclab.mailroom;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One try at sending through one transport, as listed in {@link SendResult#attempts()} and
 * {@link SendFailedException#attempts()}.
 *
 * <p>An attempt keeps only codes (the failure kind, status code and provider code), never a provider's message text,
 * because that text may name a recipient.
 *
 * @since 0.1.0
 */
public final class Attempt {

    private final String transportName;
    private final String providerId;
    private final Instant startedAt;
    private final Duration duration;
    private final FailureKind failureKind;
    private final Integer statusCode;
    private final String providerCode;

    private Attempt(String transportName, String providerId, Instant startedAt, Duration duration,
            FailureKind failureKind, Integer statusCode, String providerCode) {
        this.transportName = Objects.requireNonNull(transportName, "transportName");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.duration = Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        this.failureKind = failureKind;
        this.statusCode = statusCode;
        this.providerCode = providerCode;
    }

    /**
     * Records a successful try.
     *
     * @param transportName the configured transport name, such as {@code primary}
     * @param providerId    the provider id, such as {@code sendgrid}
     * @param startedAt     when the try started
     * @param duration      how long it took
     * @return the attempt
     * @since 0.1.0
     */
    public static Attempt succeeded(String transportName, String providerId, Instant startedAt, Duration duration) {
        return new Attempt(transportName, providerId, startedAt, duration, null, null, null);
    }

    /**
     * Records a failed try, copying the failure's kind, status code and provider code (not its message).
     *
     * @param transportName the configured transport name
     * @param providerId    the provider id
     * @param startedAt     when the try started
     * @param duration      how long it took
     * @param failure       what went wrong
     * @return the attempt
     * @since 0.1.0
     */
    public static Attempt failed(String transportName, String providerId, Instant startedAt, Duration duration,
            SendFailedException failure) {
        Objects.requireNonNull(failure, "failure");
        return new Attempt(transportName, providerId, startedAt, duration, failure.kind(),
                failure.statusCode().isPresent() ? failure.statusCode().getAsInt() : null,
                failure.providerCode().orElse(null));
    }

    /**
     * Returns the configured transport name.
     *
     * @return the name, such as {@code primary}
     * @since 0.1.0
     */
    public String transportName() {
        return transportName;
    }

    /**
     * Returns the provider id.
     *
     * @return the id, such as {@code sendgrid}
     * @since 0.1.0
     */
    public String providerId() {
        return providerId;
    }

    /**
     * Returns when the try started.
     *
     * @return the start time
     * @since 0.1.0
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Returns how long the try took.
     *
     * @return the duration, never negative
     * @since 0.1.0
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Tells whether the provider accepted the message on this try.
     *
     * @return true for a success
     * @since 0.1.0
     */
    public boolean succeeded() {
        return failureKind == null;
    }

    /**
     * Returns how the try failed.
     *
     * @return the failure kind; empty for a success
     * @since 0.1.0
     */
    public Optional<FailureKind> failureKind() {
        return Optional.ofNullable(failureKind);
    }

    /**
     * Returns the HTTP status or SMTP reply code of a failed try, if there was one.
     *
     * @return the status code
     * @since 0.1.0
     */
    public OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    /**
     * Returns the provider's own error code of a failed try, if it gave one.
     *
     * @return the provider code
     * @since 0.1.0
     */
    public Optional<String> providerCode() {
        return Optional.ofNullable(providerCode);
    }

    /**
     * Two attempts are equal when every field is equal.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Attempt)) {
            return false;
        }
        Attempt that = (Attempt) other;
        return transportName.equals(that.transportName) && providerId.equals(that.providerId)
                && startedAt.equals(that.startedAt) && duration.equals(that.duration)
                && failureKind == that.failureKind && Objects.equals(statusCode, that.statusCode)
                && Objects.equals(providerCode, that.providerCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transportName, providerId, startedAt, duration, failureKind, statusCode, providerCode);
    }

    /**
     * Describes the attempt, such as {@code Attempt[primary/sendgrid UNAVAILABLE status=503 in PT0.2S]}.
     *
     * @return a description without personal data
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("Attempt[").append(transportName).append('/').append(providerId)
                .append(' ').append(failureKind == null ? "SUCCEEDED" : failureKind.name());
        if (statusCode != null) {
            s.append(" status=").append(statusCode);
        }
        if (providerCode != null) {
            s.append(" code=").append(providerCode);
        }
        return s.append(" in ").append(duration).append(']').toString();
    }
}
