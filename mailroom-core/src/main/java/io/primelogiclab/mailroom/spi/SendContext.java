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
 * <p>The router builds a context per attempt with {@link #builder()}. Transports should respect {@link #timeLeft()}
 * when they set their own connect and read timeouts, and use {@link #clock()} instead of the system clock so tests can
 * control time.
 *
 * @param attempt  which attempt this is for this email on this transport, counting from 1
 * @param deadline when the whole send must be finished, if the caller set a deadline
 * @param executor the executor for asynchronous work, such as {@link MailTransport#sendAsync}
 * @param clock    the clock to use for timestamps and timeouts
 * @since 0.1.0
 */
public record SendContext(int attempt, Optional<Instant> deadline, Executor executor, Clock clock) {

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

    /**
     * Checks every value.
     *
     * @throws IllegalArgumentException if {@code attempt} is below 1
     * @since 0.1.0
     */
    public SendContext {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(clock, "clock");
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
     * Returns the time left before the deadline, measured with {@link #clock()}.
     *
     * @return the time left, zero once the deadline has passed; empty without a deadline
     * @since 0.1.0
     */
    public Optional<Duration> timeLeft() {
        return deadline.map(d -> {
            var left = Duration.between(clock.instant(), d);
            return left.isNegative() ? Duration.ZERO : left;
        });
    }

    /**
     * Describes the context, such as {@code SendContext[attempt=2, deadline=2026-11-21T12:00:30Z]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "SendContext[attempt=" + attempt + ", deadline=" + deadline.map(Instant::toString).orElse("none") + "]";
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
            return new SendContext(attempt, Optional.ofNullable(deadline), executor, clock);
        }
    }
}
