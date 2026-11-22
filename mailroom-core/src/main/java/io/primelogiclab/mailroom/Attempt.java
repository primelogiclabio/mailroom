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
 * because that text may name a recipient. Create one with {@link #succeeded} or {@link #failed}.
 *
 * @param transportName the configured transport name, such as {@code primary}
 * @param providerId    the provider id, such as {@code sendgrid}
 * @param startedAt     when the try started
 * @param duration      how long the try took, never negative
 * @param failureKind   how the try failed; empty for a success
 * @param statusCode    the HTTP status or SMTP reply code of a failed try, if there was one
 * @param providerCode  the provider's own error code of a failed try, if it gave one
 * @since 0.1.0
 */
public record Attempt(String transportName, String providerId, Instant startedAt, Duration duration,
        Optional<FailureKind> failureKind, OptionalInt statusCode, Optional<String> providerCode) {

    /**
     * Checks every value.
     *
     * @throws IllegalArgumentException if the duration is negative
     * @since 0.1.0
     */
    public Attempt {
        Objects.requireNonNull(transportName, "transportName");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(failureKind, "failureKind");
        Objects.requireNonNull(statusCode, "statusCode");
        Objects.requireNonNull(providerCode, "providerCode");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
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
        return new Attempt(transportName, providerId, startedAt, duration, Optional.empty(), OptionalInt.empty(),
                Optional.empty());
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
        return new Attempt(transportName, providerId, startedAt, duration, Optional.of(failure.kind()),
                failure.statusCode(), failure.providerCode());
    }

    /**
     * Tells whether the provider accepted the message on this try.
     *
     * @return true for a success
     * @since 0.1.0
     */
    public boolean succeeded() {
        return failureKind.isEmpty();
    }

    /**
     * Describes the attempt, such as {@code Attempt[primary/sendgrid UNAVAILABLE status=503 in PT0.2S]}.
     *
     * @return a description without personal data
     */
    @Override
    public String toString() {
        var s = new StringBuilder("Attempt[").append(transportName).append('/').append(providerId).append(' ')
                .append(failureKind.map(FailureKind::name).orElse("SUCCEEDED"));
        statusCode.ifPresent(code -> s.append(" status=").append(code));
        providerCode.ifPresent(code -> s.append(" code=").append(code));
        return s.append(" in ").append(duration).append(']').toString();
    }
}
