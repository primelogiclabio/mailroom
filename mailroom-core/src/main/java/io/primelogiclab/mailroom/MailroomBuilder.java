package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.DefaultMailroom;
import io.primelogiclab.mailroom.internal.NamedTransport;
import io.primelogiclab.mailroom.spi.MailTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Builds a {@link Mailroom} in code. Get one from {@link Mailroom#builder()}.
 *
 * @since 0.1.0
 */
public final class MailroomBuilder {

    private Address defaultFrom;
    private final List<MailTransport> transports = new ArrayList<MailTransport>();
    private String[] defaultRoute;
    private Duration retryBackoff;
    private Duration maxInlineWait;
    private boolean failoverOnUnknownOutcome;
    private Executor executor;
    private Clock clock;

    MailroomBuilder() {
    }

    /**
     * Sets the sender for emails that don't set one.
     *
     * @param address the sender, such as {@code PrimeLogicLab <no-reply@primelogiclab.io>}
     * @return this builder
     * @throws InvalidEmailException if the address is not valid
     * @since 0.1.0
     */
    public MailroomBuilder defaultFrom(String address) {
        this.defaultFrom = Address.parse(Objects.requireNonNull(address, "address"));
        return this;
    }

    /**
     * Sets the sender for emails that don't set one.
     *
     * @param address the sender
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder defaultFrom(Address address) {
        this.defaultFrom = Objects.requireNonNull(address, "address");
        return this;
    }

    /**
     * Adds a transport under its own {@link MailTransport#name()}.
     *
     * @param transport the transport
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder transport(MailTransport transport) {
        transports.add(Objects.requireNonNull(transport, "transport"));
        return this;
    }

    /**
     * Adds a transport under a name of your choosing, such as {@code primary}. Routes, results, failures and logs use
     * this name.
     *
     * @param name      the name
     * @param transport the transport, such as one built by a provider module's own builder
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder transport(String name, MailTransport transport) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transport, "transport");
        transports.add(name.equals(transport.name()) ? transport : new NamedTransport(name, transport));
        return this;
    }

    /**
     * Sets the default failover chain. Without it, every transport is tried in the order added.
     *
     * @param transportNames transport names, in order
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder defaultRoute(String... transportNames) {
        this.defaultRoute = Objects.requireNonNull(transportNames, "transportNames").clone();
        return this;
    }

    /**
     * Sets the pause before retrying a transport that is {@link FailureKind#UNAVAILABLE}; each pause is jittered
     * from half to one and a half times this.
     *
     * @param retryBackoff the pause; 250 ms by default
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder retryBackoff(Duration retryBackoff) {
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
        return this;
    }

    /**
     * Sets the longest {@code Retry-After} mailroom waits for before retrying a {@link FailureKind#RATE_LIMITED}
     * transport; longer waits fail over instead.
     *
     * @param maxInlineWait the limit; zero (never wait) by default
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder maxInlineWait(Duration maxInlineWait) {
        this.maxInlineWait = Objects.requireNonNull(maxInlineWait, "maxInlineWait");
        return this;
    }

    /**
     * Sets whether a {@link FailureKind#UNKNOWN_OUTCOME} fails over to the next transport. That risks a duplicate in
     * the recipient's inbox, so it is off by default.
     *
     * @param failover true to fail over
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder failoverOnUnknownOutcome(boolean failover) {
        this.failoverOnUnknownOutcome = failover;
        return this;
    }

    /**
     * Sets the executor for {@link Mailroom#sendAsync}. Mailroom never shuts down an executor you supply. Without
     * one, mailroom creates a virtual-thread-per-task executor on Java 21 and newer, or a small pool on Java 8, and
     * shuts it down on close.
     *
     * @param executor the executor
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder executor(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    /**
     * Sets the clock for attempt times.
     *
     * @param clock the clock; system UTC by default
     * @return this builder
     * @since 0.1.0
     */
    public MailroomBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Builds the mailroom.
     *
     * @return the mailroom
     * @throws IllegalStateException    if no transport was added
     * @throws IllegalArgumentException if two transports share a name, or the default route names an unknown one
     * @since 0.1.0
     */
    public Mailroom build() {
        if (transports.isEmpty()) {
            throw new IllegalStateException("A Mailroom needs at least one transport");
        }
        return DefaultMailroom.create(transports, defaultRoute, retryBackoff, maxInlineWait,
                failoverOnUnknownOutcome, executor, clock, defaultFrom);
    }
}
