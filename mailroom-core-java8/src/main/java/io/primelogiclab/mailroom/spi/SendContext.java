package io.primelogiclab.mailroom.spi;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * What the router hands a transport with each send, so transports don't reach for globals: which attempt this is,
 * the deadline, the executor for asynchronous work and the clock.
 *
 * <p>The router builds a context per attempt. Transports should respect {@link #timeLeft()} when they set their own
 * connect and read timeouts, and use {@link #clock()} instead of the system clock so tests can control time.
 *
 * @since 0.1.0
 */
public final class SendContext {

    private static final Executor CALLING_THREAD = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public String toString() {
            return "calling thread";
        }
    };

    private final int attempt;
    private final Instant deadline;
    private final Executor executor;
    private final Clock clock;

    private SendContext(Builder b) {
        this.attempt = b.attempt;
        this.deadline = b.deadline;
        this.executor = b.executor;
        this.clock = b.clock;
    }

    /**
     * Starts a context: attempt 1, no deadline, work on the calling thread, the system UTC clock.
     *
     * @return a builder
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns which attempt this is for this email on this transport, counting from 1.
     *
     * @return the attempt number
     * @since 0.1.0
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns when the whole send must be finished, if the caller set a deadline.
     *
     * @return the deadline
     * @since 0.1.0
     */
    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * Returns the time left before the deadline, measured with {@link #clock()}.
     *
     * @return the time left, zero once the deadline has passed; empty without a deadline
     * @since 0.1.0
     */
    public Optional<Duration> timeLeft() {
        if (deadline == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(clock.instant(), deadline);
        return Optional.of(left.isNegative() ? Duration.ZERO : left);
    }

    /**
     * Returns the executor for asynchronous work, such as {@link MailTransport#sendAsync}.
     *
     * @return the executor
     * @since 0.1.0
     */
    public Executor executor() {
        return executor;
    }

    /**
     * Returns the clock to use for timestamps and timeouts.
     *
     * @return the clock
     * @since 0.1.0
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Two contexts are equal when they have the same attempt number and deadline, and equal executors and clocks.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SendContext)) {
            return false;
        }
        SendContext that = (SendContext) other;
        return attempt == that.attempt && Objects.equals(deadline, that.deadline)
                && executor.equals(that.executor) && clock.equals(that.clock);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attempt, deadline, executor, clock);
    }

    /**
     * Describes the context, such as {@code SendContext[attempt=2, deadline=2026-11-21T12:00:30Z]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "SendContext[attempt=" + attempt + ", deadline=" + (deadline == null ? "none" : deadline) + "]";
    }

    /**
     * Collects a context's values.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private int attempt = 1;
        private Instant deadline;
        private Executor executor = CALLING_THREAD;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Sets which attempt this is.
         *
         * @param attempt the attempt number, from 1
         * @return this builder
         * @throws IllegalArgumentException if {@code attempt} is below 1
         * @since 0.1.0
         */
        public Builder attempt(int attempt) {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be at least 1");
            }
            this.attempt = attempt;
            return this;
        }

        /**
         * Sets when the whole send must be finished.
         *
         * @param deadline the deadline
         * @return this builder
         * @since 0.1.0
         */
        public Builder deadline(Instant deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /**
         * Sets the executor for asynchronous work.
         *
         * @param executor the executor
         * @return this builder
         * @since 0.1.0
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Sets the clock.
         *
         * @param clock the clock
         * @return this builder
         * @since 0.1.0
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the context.
         *
         * @return the context
         * @since 0.1.0
         */
        public SendContext build() {
            return new SendContext(this);
        }
    }
}
