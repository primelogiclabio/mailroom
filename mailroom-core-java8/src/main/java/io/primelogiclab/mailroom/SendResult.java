package io.primelogiclab.mailroom;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a successful send: which transport and provider took the message, the provider's message id if it
 * returns one, and every attempt made on the way.
 *
 * <p>A result means "the provider took it", not "delivered": see {@link Status}. Transports build the result for
 * their own try; the router then adds the full list of attempts.
 *
 * @since 0.1.0
 */
public final class SendResult {

    private final String transportName;
    private final String providerId;
    private final String providerMessageId;
    private final Status status;
    private final Instant acceptedAt;
    private final List<Attempt> attempts;

    private SendResult(Builder b) {
        this.transportName = b.transportName;
        this.providerId = b.providerId;
        this.providerMessageId = b.providerMessageId;
        this.status = b.status;
        this.acceptedAt = b.acceptedAt;
        this.attempts = Collections.unmodifiableList(new ArrayList<Attempt>(b.attempts));
    }

    /**
     * Starts a result.
     *
     * @return a builder with status {@link Status#ACCEPTED} and no attempts
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a builder holding this result's values, for example to add the attempts.
     *
     * @return a builder with every field of this result
     * @since 0.1.0
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.transportName = transportName;
        b.providerId = providerId;
        b.providerMessageId = providerMessageId;
        b.status = status;
        b.acceptedAt = acceptedAt;
        b.attempts.addAll(attempts);
        return b;
    }

    /**
     * Returns the configured name of the transport that took the message.
     *
     * @return the name, such as {@code primary}
     * @since 0.1.0
     */
    public String transportName() {
        return transportName;
    }

    /**
     * Returns the id of the provider that took the message.
     *
     * @return the id, such as {@code sendgrid}
     * @since 0.1.0
     */
    public String providerId() {
        return providerId;
    }

    /**
     * Returns the provider's id for the message, for matching delivery events later.
     *
     * @return the id; empty when the provider returns none (Microsoft Graph {@code sendMail}, for example)
     * @since 0.1.0
     */
    public Optional<String> providerMessageId() {
        return Optional.ofNullable(providerMessageId);
    }

    /**
     * Returns whether the provider took the message for now or for later.
     *
     * @return the status
     * @since 0.1.0
     */
    public Status status() {
        return status;
    }

    /**
     * Returns when the provider took the message.
     *
     * @return the time
     * @since 0.1.0
     */
    public Instant acceptedAt() {
        return acceptedAt;
    }

    /**
     * Returns every attempt, in order: the failed ones the router moved past, then the one that succeeded.
     *
     * @return the attempts; the list cannot be changed
     * @since 0.1.0
     */
    public List<Attempt> attempts() {
        return attempts;
    }

    /**
     * Two results are equal when every field is equal.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SendResult)) {
            return false;
        }
        SendResult that = (SendResult) other;
        return transportName.equals(that.transportName) && providerId.equals(that.providerId)
                && Objects.equals(providerMessageId, that.providerMessageId) && status == that.status
                && acceptedAt.equals(that.acceptedAt) && attempts.equals(that.attempts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transportName, providerId, providerMessageId, status, acceptedAt, attempts);
    }

    /**
     * Describes the result, such as
     * {@code SendResult[transport=primary, provider=sendgrid, status=ACCEPTED, messageId=abc, attempts=2]}.
     *
     * @return a description without personal data
     */
    @Override
    public String toString() {
        return "SendResult[transport=" + transportName + ", provider=" + providerId + ", status=" + status
                + ", messageId=" + (providerMessageId == null ? "none" : providerMessageId) + ", attempts="
                + attempts.size() + "]";
    }

    /**
     * Collects a result's fields. The transport name, provider id and accepted time are required.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private String transportName;
        private String providerId;
        private String providerMessageId;
        private Status status = Status.ACCEPTED;
        private Instant acceptedAt;
        private final List<Attempt> attempts = new ArrayList<Attempt>();

        private Builder() {
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
         * Sets the provider's id for the message.
         *
         * @param providerMessageId the id; not blank
         * @return this builder
         * @throws IllegalArgumentException if the id is blank
         * @since 0.1.0
         */
        public Builder providerMessageId(String providerMessageId) {
            Objects.requireNonNull(providerMessageId, "providerMessageId");
            if (providerMessageId.trim().isEmpty()) {
                throw new IllegalArgumentException("providerMessageId must not be blank");
            }
            this.providerMessageId = providerMessageId;
            return this;
        }

        /**
         * Sets whether the provider took the message for now or for later.
         *
         * @param status the status; {@link Status#ACCEPTED} if never set
         * @return this builder
         * @since 0.1.0
         */
        public Builder status(Status status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        /**
         * Sets when the provider took the message.
         *
         * @param acceptedAt the time
         * @return this builder
         * @since 0.1.0
         */
        public Builder acceptedAt(Instant acceptedAt) {
            this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
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
         * Builds the result.
         *
         * @return the result
         * @throws IllegalStateException if the transport name, provider id or accepted time is missing
         * @since 0.1.0
         */
        public SendResult build() {
            if (transportName == null || providerId == null || acceptedAt == null) {
                throw new IllegalStateException("transportName, providerId and acceptedAt are required");
            }
            return new SendResult(this);
        }
    }
}
