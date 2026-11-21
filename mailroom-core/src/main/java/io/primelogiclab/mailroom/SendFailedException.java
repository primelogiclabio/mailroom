package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.Masking;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Sending failed. {@link #kind()} says how, so the router and the caller's queue can decide what to do next; the
 * other fields say where and carry the provider's codes.
 *
 * <pre>{@code
 * throw SendFailedException.builder(FailureKind.RATE_LIMITED, "SendGrid answered 429")
 *     .transportName("primary")
 *     .providerId("sendgrid")
 *     .statusCode(429)
 *     .retryAfter(Duration.ofSeconds(1))
 *     .build();
 * }</pre>
 *
 * <p>The message is safe to log: any email address in it is masked ({@code p***@example.com}). Transports must not
 * put credentials in the message, and must not pass a cause whose own message holds credentials or addresses; wrap
 * such causes instead. The contract tests check this for every transport.
 *
 * @since 0.1.0
 */
public class SendFailedException extends MailroomException {

    private static final long serialVersionUID = 1L;

    /** How the send failed. */
    private final FailureKind kind;
    /** The masked message without the kind and transport prefix. */
    private final String detail;
    /** The configured transport name, or null. */
    private final String transportName;
    /** The provider id, or null. */
    private final String providerId;
    /** The HTTP status or SMTP reply code, or null. */
    private final Integer statusCode;
    /** The provider's error code, or null. */
    private final String providerCode;
    /** How long the provider asked to wait, or null. */
    private final Duration retryAfter;
    private final transient List<Attempt> attempts;

    private SendFailedException(Builder b) {
        super(message(b.kind, b.transportName, Masking.addressesIn(b.message)), b.cause);
        this.kind = b.kind;
        this.detail = Masking.addressesIn(b.message);
        this.transportName = b.transportName;
        this.providerId = b.providerId;
        this.statusCode = b.statusCode;
        this.providerCode = b.providerCode;
        this.retryAfter = b.retryAfter;
        this.attempts = Collections.unmodifiableList(new ArrayList<Attempt>(b.attempts));
    }

    /**
     * Starts a failure of the given kind.
     *
     * @param kind    how the send failed
     * @param message what happened, such as {@code SendGrid answered 503}; addresses in it are masked
     * @return a builder
     * @since 0.1.0
     */
    public static Builder builder(FailureKind kind, String message) {
        return new Builder(kind, message);
    }

    /**
     * Starts a builder holding this failure's values and cause, for example to add the attempts.
     *
     * @return a builder with every field of this failure
     * @since 0.1.0
     */
    public Builder toBuilder() {
        Builder b = new Builder(kind, detail);
        b.transportName = transportName;
        b.providerId = providerId;
        b.statusCode = statusCode;
        b.providerCode = providerCode;
        b.retryAfter = retryAfter;
        b.cause = getCause();
        b.attempts.addAll(attempts());
        return b;
    }

    /**
     * Returns how the send failed.
     *
     * @return the failure kind
     * @since 0.1.0
     */
    public FailureKind kind() {
        return kind;
    }

    /**
     * Returns the configured name of the transport that failed.
     *
     * @return the name, such as {@code primary}; empty if the failure isn't tied to one transport
     * @since 0.1.0
     */
    public Optional<String> transportName() {
        return Optional.ofNullable(transportName);
    }

    /**
     * Returns the id of the provider that failed.
     *
     * @return the id, such as {@code sendgrid}
     * @since 0.1.0
     */
    public Optional<String> providerId() {
        return Optional.ofNullable(providerId);
    }

    /**
     * Returns the HTTP status or SMTP reply code, if the provider answered with one.
     *
     * @return the status code
     * @since 0.1.0
     */
    public OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    /**
     * Returns the provider's own error code, if it gave one.
     *
     * @return the provider code
     * @since 0.1.0
     */
    public Optional<String> providerCode() {
        return Optional.ofNullable(providerCode);
    }

    /**
     * Returns how long the provider asked to wait before trying again, from a {@code Retry-After} header or similar.
     *
     * @return the wait
     * @since 0.1.0
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Returns every attempt the router made before giving up, in order.
     *
     * @return the attempts, possibly empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<Attempt> attempts() {
        return attempts == null ? Collections.<Attempt>emptyList() : attempts;
    }

    private static String message(FailureKind kind, String transportName, String detail) {
        return kind.name() + (transportName == null ? "" : " via " + transportName) + ": " + detail;
    }

    /**
     * Collects a failure's fields. Only the kind and message are required.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final FailureKind kind;
        private final String message;
        private String transportName;
        private String providerId;
        private Integer statusCode;
        private String providerCode;
        private Duration retryAfter;
        private Throwable cause;
        private final List<Attempt> attempts = new ArrayList<Attempt>();

        private Builder(FailureKind kind, String message) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.message = Objects.requireNonNull(message, "message");
        }

        /**
         * Sets the configured transport name.
         *
         * @param transportName the name, such as {@code primary}
         * @return this builder
         * @since 0.1.0
         */
        public Builder transportName(String transportName) {
            this.transportName = Objects.requireNonNull(transportName, "transportName");
            return this;
        }

        /**
         * Sets the provider id.
         *
         * @param providerId the id, such as {@code sendgrid}
         * @return this builder
         * @since 0.1.0
         */
        public Builder providerId(String providerId) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            return this;
        }

        /**
         * Sets the HTTP status or SMTP reply code.
         *
         * @param statusCode the code, from 100 to 999
         * @return this builder
         * @throws IllegalArgumentException if the code is outside 100 to 999
         * @since 0.1.0
         */
        public Builder statusCode(int statusCode) {
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("statusCode must be from 100 to 999");
            }
            this.statusCode = statusCode;
            return this;
        }

        /**
         * Sets the provider's own error code.
         *
         * @param providerCode the code, such as {@code ApplicationThrottled}
         * @return this builder
         * @since 0.1.0
         */
        public Builder providerCode(String providerCode) {
            this.providerCode = Objects.requireNonNull(providerCode, "providerCode");
            return this;
        }

        /**
         * Sets how long the provider asked to wait before trying again.
         *
         * @param retryAfter the wait, not negative
         * @return this builder
         * @throws IllegalArgumentException if the wait is negative
         * @since 0.1.0
         */
        public Builder retryAfter(Duration retryAfter) {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
            this.retryAfter = retryAfter;
            return this;
        }

        /**
         * Sets the underlying failure. Its message must not hold credentials or addresses; wrap it if it might.
         *
         * @param cause the cause
         * @return this builder
         * @since 0.1.0
         */
        public Builder cause(Throwable cause) {
            this.cause = Objects.requireNonNull(cause, "cause");
            return this;
        }

        /**
         * Replaces the attempts.
         *
         * @param attempts every attempt, in order
         * @return this builder
         * @since 0.1.0
         */
        public Builder attempts(List<Attempt> attempts) {
            Objects.requireNonNull(attempts, "attempts");
            this.attempts.clear();
            for (Attempt attempt : attempts) {
                this.attempts.add(Objects.requireNonNull(attempt, "attempt"));
            }
            return this;
        }

        /**
         * Builds the exception, ready to throw.
         *
         * @return the exception
         * @since 0.1.0
         */
        public SendFailedException build() {
            return new SendFailedException(this);
        }
    }
}
