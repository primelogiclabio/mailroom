package io.primelogiclab.mailroom.test;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A transport that does what its script says, one step per call: succeed, fail with a given {@link FailureKind}, or
 * wait first. Use it to test failover, retries and error handling without a real provider.
 *
 * <pre>{@code
 * ScriptedTransport primary = ScriptedTransport.builder("primary")
 *     .fail(FailureKind.UNAVAILABLE, 503)   // call 1
 *     .fail(FailureKind.UNAVAILABLE, 503)   // call 2
 *     .succeed()                            // call 3
 *     .build();                             // later calls succeed
 * }</pre>
 *
 * <p>Failures are thrown as a {@link SendFailedException} with this transport's name and provider id, exactly as a
 * real transport would throw them. Delays use {@link Thread#sleep}, so keep them short. The transport is
 * thread-safe; concurrent calls take the next steps in turn.
 *
 * @since 0.1.0
 */
public final class ScriptedTransport implements MailTransport {

    private final String name;
    private final String providerId;
    private final TransportCapabilities capabilities;
    private final List<Step> script;
    private final Step afterScript;
    private final Precheck precheck;
    private final AtomicInteger position = new AtomicInteger();
    private final AtomicInteger messageIds = new AtomicInteger();
    private final List<Email> received = new CopyOnWriteArrayList<Email>();
    private final List<SendContext> contexts = new CopyOnWriteArrayList<SendContext>();

    private ScriptedTransport(Builder b) {
        this.name = b.name;
        this.providerId = b.providerId;
        this.capabilities = Defaults.build(b.capabilities, b.understood);
        this.script = Collections.unmodifiableList(new ArrayList<Step>(b.script));
        this.afterScript = b.afterScript;
        this.precheck = b.precheck;
    }

    /**
     * Starts a script. Without steps, every call succeeds.
     *
     * @param name the configured name, such as {@code primary}
     * @return a builder
     * @since 0.1.0
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public TransportCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Returns {@link Precheck#ok()}, or the rejection set with {@link Builder#rejectPrecheck}.
     *
     * @param email the email
     * @return the scripted precheck
     */
    @Override
    public Precheck precheck(Email email) {
        return precheck;
    }

    /**
     * Records the call and runs the next step of the script.
     *
     * @param email   the email
     * @param context the send context
     * @return the result, for a succeeding step
     * @throws SendFailedException for a failing step
     */
    @Override
    public SendResult send(Email email, SendContext context) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(context, "context");
        received.add(email);
        contexts.add(context);
        int index = position.getAndIncrement();
        Step step = index < script.size() ? script.get(index) : afterScript;
        return step.run(this, context);
    }

    /**
     * Returns how many times {@link #send} was called.
     *
     * @return the call count
     * @since 0.1.0
     */
    public int calls() {
        return received.size();
    }

    /**
     * Returns the email of every call, in order.
     *
     * @return a copy of the emails
     * @since 0.1.0
     */
    public List<Email> received() {
        return Collections.unmodifiableList(new ArrayList<Email>(received));
    }

    /**
     * Returns the context of every call, in order, for example to check attempt numbers.
     *
     * @return a copy of the contexts
     * @since 0.1.0
     */
    public List<SendContext> contexts() {
        return Collections.unmodifiableList(new ArrayList<SendContext>(contexts));
    }

    /**
     * Fails unless {@link #send} was called exactly this many times.
     *
     * @param expected the expected count
     * @throws AssertionError if the count differs
     * @since 0.1.0
     */
    public void assertCalls(int expected) {
        int actual = received.size();
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " call(s) to '" + name + "' but there were " + actual);
        }
    }

    /**
     * Starts the script over and forgets the calls so far.
     *
     * @since 0.1.0
     */
    public void reset() {
        position.set(0);
        messageIds.set(0);
        received.clear();
        contexts.clear();
    }

    /**
     * Describes the transport, such as {@code ScriptedTransport[primary, steps=3, calls=1]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "ScriptedTransport[" + name + ", steps=" + script.size() + ", calls=" + received.size() + "]";
    }

    /** One scripted call: an optional wait, then a success or a failure. */
    private static final class Step {
        private final Duration delay;
        private final FailureKind kind;
        private final Integer statusCode;
        private final Duration retryAfter;
        private final SendFailedException exception;

        Step(Duration delay, FailureKind kind, Integer statusCode, Duration retryAfter,
                SendFailedException exception) {
            this.delay = delay;
            this.kind = kind;
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
            this.exception = exception;
        }

        static Step success(Duration delay) {
            return new Step(delay, null, null, null, null);
        }

        SendResult run(ScriptedTransport transport, SendContext context) {
            if (delay != null && !delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw SendFailedException.builder(FailureKind.UNKNOWN_OUTCOME, "scripted delay interrupted")
                            .transportName(transport.name).providerId(transport.providerId).cause(e).build();
                }
            }
            if (exception != null) {
                throw exception;
            }
            if (kind != null) {
                SendFailedException.Builder failure = SendFailedException.builder(kind, "scripted " + kind.name())
                        .transportName(transport.name).providerId(transport.providerId);
                if (statusCode != null) {
                    failure.statusCode(statusCode);
                }
                if (retryAfter != null) {
                    failure.retryAfter(retryAfter);
                }
                throw failure.build();
            }
            return SendResult.builder().transportName(transport.name).providerId(transport.providerId)
                    .providerMessageId("scripted-" + transport.messageIds.incrementAndGet())
                    .acceptedAt(context.clock().instant()).build();
        }
    }

    /**
     * Collects the script: each step is one call, in order.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final String name;
        private String providerId = "scripted";
        private TransportCapabilities capabilities;
        private final List<Class<? extends EmailExtension>> understood =
                new ArrayList<Class<? extends EmailExtension>>();
        private final List<Step> script = new ArrayList<Step>();
        private Step afterScript = Step.success(null);
        private Precheck precheck = Precheck.ok();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /**
         * Sets the provider id.
         *
         * @param providerId the id; {@code scripted} by default
         * @return this builder
         * @since 0.1.0
         */
        public Builder providerId(String providerId) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            return this;
        }

        /**
         * Replaces the default capabilities (every well-known capability except batch sending).
         *
         * @param capabilities what the transport claims to carry
         * @return this builder
         * @since 0.1.0
         */
        public Builder capabilities(TransportCapabilities capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            return this;
        }

        /**
         * Adds extension classes the transport understands.
         *
         * @param extensionTypes the classes
         * @return this builder
         * @since 0.1.0
         */
        @SafeVarargs
        public final Builder understands(Class<? extends EmailExtension>... extensionTypes) {
            for (Class<? extends EmailExtension> type : Objects.requireNonNull(extensionTypes, "extensionTypes")) {
                understood.add(Objects.requireNonNull(type, "extensionType"));
            }
            return this;
        }

        /**
         * Makes {@link ScriptedTransport#precheck} reject every email, so the router skips this transport.
         *
         * @param reason why, such as {@code too large}
         * @return this builder
         * @since 0.1.0
         */
        public Builder rejectPrecheck(String reason) {
            this.precheck = Precheck.reject(reason);
            return this;
        }

        /**
         * The next call succeeds.
         *
         * @return this builder
         * @since 0.1.0
         */
        public Builder succeed() {
            script.add(Step.success(null));
            return this;
        }

        /**
         * The next call waits, then succeeds.
         *
         * @param delay how long to wait
         * @return this builder
         * @since 0.1.0
         */
        public Builder succeedAfter(Duration delay) {
            script.add(Step.success(nonNegative(delay)));
            return this;
        }

        /**
         * The next call fails with this kind.
         *
         * @param kind how it fails
         * @return this builder
         * @since 0.1.0
         */
        public Builder fail(FailureKind kind) {
            script.add(new Step(null, Objects.requireNonNull(kind, "kind"), null, null, null));
            return this;
        }

        /**
         * The next call fails with this kind and HTTP status or SMTP reply code.
         *
         * @param kind       how it fails
         * @param statusCode the code, from 100 to 999
         * @return this builder
         * @since 0.1.0
         */
        public Builder fail(FailureKind kind, int statusCode) {
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("statusCode must be from 100 to 999");
            }
            script.add(new Step(null, Objects.requireNonNull(kind, "kind"), statusCode, null, null));
            return this;
        }

        /**
         * The next call fails as {@link FailureKind#RATE_LIMITED} with HTTP 429 and this retry-after.
         *
         * @param retryAfter how long the provider asks to wait
         * @return this builder
         * @since 0.1.0
         */
        public Builder rateLimited(Duration retryAfter) {
            script.add(new Step(null, FailureKind.RATE_LIMITED, 429, nonNegative(retryAfter), null));
            return this;
        }

        /**
         * The next call waits, then fails with this kind; use {@link FailureKind#UNKNOWN_OUTCOME} for a timeout after
         * the request was sent.
         *
         * @param delay how long to wait
         * @param kind  how it fails
         * @return this builder
         * @since 0.1.0
         */
        public Builder failAfter(Duration delay, FailureKind kind) {
            script.add(new Step(nonNegative(delay), Objects.requireNonNull(kind, "kind"), null, null, null));
            return this;
        }

        /**
         * The next call throws this exact exception.
         *
         * @param failure the exception
         * @return this builder
         * @since 0.1.0
         */
        public Builder fail(SendFailedException failure) {
            script.add(new Step(null, null, null, null, Objects.requireNonNull(failure, "failure")));
            return this;
        }

        /**
         * Every call after the script succeeds (the default).
         *
         * @return this builder
         * @since 0.1.0
         */
        public Builder thenAlwaysSucceed() {
            afterScript = Step.success(null);
            return this;
        }

        /**
         * Every call after the script fails with this kind.
         *
         * @param kind how they fail
         * @return this builder
         * @since 0.1.0
         */
        public Builder thenAlwaysFail(FailureKind kind) {
            afterScript = new Step(null, Objects.requireNonNull(kind, "kind"), null, null, null);
            return this;
        }

        /**
         * Builds the transport.
         *
         * @return the transport
         * @since 0.1.0
         */
        public ScriptedTransport build() {
            return new ScriptedTransport(this);
        }

        private static Duration nonNegative(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
            return duration;
        }
    }
}
