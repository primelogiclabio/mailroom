package io.primelogiclab.mailroom.internal;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Attempt;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.MailroomListener;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routes an email over an ordered chain of transports: it drops the transports that can't carry the email, then
 * tries the rest in order, retrying or failing over as each failure's {@link FailureKind} says. Not API: the
 * {@code Mailroom} facade wraps it.
 *
 * <p>Rules for each failure kind:
 * <ul>
 *   <li>{@code INVALID_MESSAGE}: stop, because another provider would reject the content too;</li>
 *   <li>{@code REJECTED}, {@code AUTHENTICATION}: next transport, no retry;</li>
 *   <li>{@code RATE_LIMITED}: wait and retry once if the provider's retry-after is within {@code maxInlineWait},
 *       otherwise next transport;</li>
 *   <li>{@code UNAVAILABLE}: one retry after a short jittered backoff, then next transport;</li>
 *   <li>{@code UNKNOWN_OUTCOME}: stop, unless {@code failoverOnUnknownOutcome} is set, because the provider may already
 *       be delivering the email.</li>
 * </ul>
 * Any other exception a transport throws counts as {@code UNKNOWN_OUTCOME}.
 *
 * <p>Each skip, attempt, success and failure is logged and reported to the listeners through an {@link Observer}.
 *
 * <p>The router is immutable and keeps no state between sends, so it is thread-safe. Health tracking (circuit
 * breaker, cooling after a rate limit) and rule-based routes come later.
 */
public final class Router {

    /** Waits between attempts; replaceable so tests don't really sleep. */
    public interface Sleeper {
        /**
         * Waits.
         *
         * @param duration how long
         * @throws InterruptedException if interrupted while waiting
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Sleeper THREAD_SLEEP = new Sleeper() {
        @Override
        public void sleep(Duration duration) throws InterruptedException {
            Thread.sleep(duration.toMillis());
        }
    };

    private static final Executor CALLING_THREAD = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private enum Next { RETRY, NEXT_TRANSPORT, STOP }

    private final Map<String, MailTransport> transports;
    private final List<String> defaultRoute;
    private final Duration retryBackoff;
    private final Duration maxInlineWait;
    private final boolean failoverOnUnknownOutcome;
    private final Executor executor;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Observer observer;

    private Router(Builder b, List<String> defaultRoute) {
        this.transports = Collections.unmodifiableMap(new LinkedHashMap<String, MailTransport>(b.transports));
        this.defaultRoute = Collections.unmodifiableList(defaultRoute);
        this.retryBackoff = b.retryBackoff;
        this.maxInlineWait = b.maxInlineWait;
        this.failoverOnUnknownOutcome = b.failoverOnUnknownOutcome;
        this.executor = b.executor;
        this.clock = b.clock;
        this.sleeper = b.sleeper;
        this.observer = new Observer(b.listeners);
    }

    /**
     * Starts a router.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the transports by name, in the order they were added.
     *
     * @return the transports; the map cannot be changed
     */
    public Map<String, MailTransport> transports() {
        return transports;
    }

    /**
     * Returns the default route.
     *
     * @return transport names in order
     */
    public List<String> defaultRoute() {
        return defaultRoute;
    }

    /**
     * Sends over the default route.
     *
     * @param email the email
     * @return the result, with every attempt
     * @throws SendFailedException      if every eligible transport failed, or a failure stopped the route
     * @throws NoEligibleTransportException if no transport on the route can carry the email
     */
    public SendResult send(Email email) {
        return route(Objects.requireNonNull(email, "email"), defaultRoute);
    }

    /**
     * Sends over a pinned route, for {@code Mailroom.via(...)}.
     *
     * @param email the email
     * @param route transport names, in order
     * @return the result, with every attempt
     * @throws IllegalArgumentException if the route is empty, repeats a name or names an unknown transport
     * @throws SendFailedException      if every eligible transport failed, or a failure stopped the route
     * @throws NoEligibleTransportException if no transport on the route can carry the email
     */
    public SendResult send(Email email, List<String> route) {
        Objects.requireNonNull(email, "email");
        checkRoute(route, transports);
        return route(email, route);
    }

    private SendResult route(Email email, List<String> route) {
        Map<String, String> skipped = new LinkedHashMap<String, String>();
        List<MailTransport> eligible = new ArrayList<MailTransport>();
        for (String name : route) {
            MailTransport transport = transports.get(name);
            String reason = skipReason(transport, email);
            if (reason == null) {
                eligible.add(transport);
            } else {
                String masked = Masking.addressesIn(reason);
                skipped.put(name, masked);
                observer.skipped(email, name, masked);
            }
        }
        if (eligible.isEmpty()) {
            NoEligibleTransportException none = new NoEligibleTransportException(skipped);
            observer.failed(email, none);
            throw none;
        }

        List<Attempt> attempts = new ArrayList<Attempt>();
        SendFailedException last = null;
        for (int t = 0; t < eligible.size(); t++) {
            MailTransport transport = eligible.get(t);
            boolean lastTransport = t == eligible.size() - 1;
            int attempt = 1;
            while (true) {
                Instant startedAt = clock.instant();
                long start = System.nanoTime();
                SendFailedException failure;
                try {
                    SendResult result = transport.send(email, context(attempt));
                    Attempt succeeded = Attempt.succeeded(transport.name(), transport.providerId(), startedAt,
                            elapsed(start));
                    attempts.add(succeeded);
                    observer.attempted(email, attempt, succeeded, null, null);
                    SendResult withAttempts = result.toBuilder().attempts(attempts).build();
                    observer.succeeded(email, withAttempts);
                    return withAttempts;
                } catch (SendFailedException e) {
                    failure = e;
                } catch (RuntimeException e) {
                    failure = SendFailedException.builder(FailureKind.UNKNOWN_OUTCOME,
                            e.getClass().getSimpleName() + " from transport").transportName(transport.name())
                            .providerId(transport.providerId()).cause(e).build();
                }
                Attempt failed = Attempt.failed(transport.name(), transport.providerId(), startedAt, elapsed(start),
                        failure);
                attempts.add(failed);
                last = failure;
                Next next = decide(failure, attempt);
                Duration wait = next != Next.RETRY ? null
                        : failure.kind() == FailureKind.RATE_LIMITED ? failure.retryAfter().get()
                        : jittered(retryBackoff);
                observer.attempted(email, attempt, failed, failure, next == Next.RETRY
                        ? "retrying in " + wait.toMillis() + " ms"
                        : next == Next.NEXT_TRANSPORT && !lastTransport ? "trying the next transport"
                        : next == Next.NEXT_TRANSPORT ? "no transports left" : "stopping");
                if (next == Next.STOP) {
                    throw fail(email, failure, attempts);
                }
                if (next == Next.NEXT_TRANSPORT) {
                    break;
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail(email, failure, attempts);
                }
                attempt++;
            }
        }
        throw fail(email, last, attempts);
    }

    /** Adds the attempts to the failure and reports it; the caller throws the result. */
    private SendFailedException fail(Email email, SendFailedException failure, List<Attempt> attempts) {
        SendFailedException withAttempts = failure.toBuilder().attempts(attempts).build();
        observer.failed(email, withAttempts);
        return withAttempts;
    }

    private Next decide(SendFailedException failure, int attempt) {
        switch (failure.kind()) {
            case INVALID_MESSAGE:
                return Next.STOP;
            case REJECTED:
            case AUTHENTICATION:
                return Next.NEXT_TRANSPORT;
            case RATE_LIMITED:
                return attempt == 1 && failure.retryAfter().isPresent()
                        && failure.retryAfter().get().compareTo(maxInlineWait) <= 0
                        ? Next.RETRY : Next.NEXT_TRANSPORT;
            case UNAVAILABLE:
                return attempt == 1 ? Next.RETRY : Next.NEXT_TRANSPORT;
            case UNKNOWN_OUTCOME:
                return failoverOnUnknownOutcome ? Next.NEXT_TRANSPORT : Next.STOP;
            default:
                return Next.STOP;
        }
    }

    /** Why a transport can't carry this email, or null if it can. */
    static String skipReason(MailTransport transport, Email email) {
        TransportCapabilities caps = transport.capabilities();
        List<String> missing = new ArrayList<String>();
        need(caps, Capability.HTML, email.html().isPresent(), missing);
        need(caps, Capability.TEXT, email.text().isPresent(), missing);
        boolean regular = false;
        boolean inline = false;
        long size = bytes(email.text().orElse(null)) + bytes(email.html().orElse(null));
        for (Attachment attachment : email.attachments()) {
            regular |= !attachment.isInline();
            inline |= attachment.isInline();
            size += attachment.size();
        }
        need(caps, Capability.ATTACHMENTS, regular, missing);
        need(caps, Capability.INLINE_IMAGES, inline, missing);
        need(caps, Capability.CUSTOM_HEADERS, !email.headers().isEmpty(), missing);
        need(caps, Capability.SCHEDULING, email.sendAt().isPresent(), missing);
        if (!missing.isEmpty()) {
            return "doesn't support " + join(missing);
        }

        List<String> notUnderstood = new ArrayList<String>();
        for (EmailExtension extension : email.extensions()) {
            if (extension.essential() && !caps.understands(extension.getClass())) {
                notUnderstood.add(extension.getClass().getSimpleName());
            }
        }
        if (!notUnderstood.isEmpty()) {
            return "doesn't understand " + join(notUnderstood) + " (essential)";
        }

        int recipients = email.recipients().size();
        if (caps.maxRecipientsPerMessage().isPresent() && recipients > caps.maxRecipientsPerMessage().getAsInt()) {
            return "has " + recipients + " recipients, over its limit of " + caps.maxRecipientsPerMessage().getAsInt();
        }
        if (caps.maxMessageSize().isPresent() && size > caps.maxMessageSize().getAsLong()) {
            return "is about " + size + " bytes, over its limit of " + caps.maxMessageSize().getAsLong();
        }

        Precheck precheck;
        try {
            precheck = transport.precheck(email);
        } catch (RuntimeException e) {
            return "precheck failed with " + e.getClass().getSimpleName();
        }
        return precheck.passed() ? null : precheck.reason().orElse("precheck rejected the email");
    }

    private static void need(TransportCapabilities caps, Capability capability, boolean needed,
            List<String> missing) {
        if (needed && !caps.supports(capability)) {
            missing.add(capability.key());
        }
    }

    private SendContext context(int attempt) {
        return SendContext.builder().attempt(attempt).executor(executor).clock(clock).build();
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
    }

    /** The backoff scaled by a random factor from 0.5 up to 1.5. */
    private static Duration jittered(Duration backoff) {
        double factor = 0.5 + ThreadLocalRandom.current().nextDouble();
        return Duration.ofNanos((long) (backoff.toNanos() * factor));
    }

    private static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String join(List<String> parts) {
        StringBuilder s = new StringBuilder();
        for (String part : parts) {
            s.append(s.length() == 0 ? "" : ", ").append(part);
        }
        return s.toString();
    }

    private static void checkRoute(List<String> route, Map<String, MailTransport> transports) {
        Objects.requireNonNull(route, "route");
        if (route.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one transport");
        }
        Set<String> seen = new HashSet<String>();
        for (String name : route) {
            if (!transports.containsKey(Objects.requireNonNull(name, "transport name"))) {
                throw new IllegalArgumentException("no transport named '" + name + "' (configured: "
                        + join(new ArrayList<String>(transports.keySet())) + ")");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("the route names '" + name + "' twice");
            }
        }
    }

    /**
     * Collects a router's transports and settings.
     */
    public static final class Builder {

        private final Map<String, MailTransport> transports = new LinkedHashMap<String, MailTransport>();
        private List<String> defaultRoute;
        private Duration retryBackoff = Duration.ofMillis(250);
        private Duration maxInlineWait = Duration.ZERO;
        private boolean failoverOnUnknownOutcome;
        private Executor executor = CALLING_THREAD;
        private Clock clock = Clock.systemUTC();
        private Sleeper sleeper = THREAD_SLEEP;
        private final List<MailroomListener> listeners = new ArrayList<MailroomListener>();

        private Builder() {
        }

        /**
         * Adds a listener; listeners are called in the order added.
         *
         * @param listener the listener
         * @return this builder
         */
        public Builder listener(MailroomListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /**
         * Adds a transport, known by its {@link MailTransport#name()}.
         *
         * @param transport the transport
         * @return this builder
         * @throws IllegalArgumentException if a transport with that name was already added
         */
        public Builder transport(MailTransport transport) {
            Objects.requireNonNull(transport, "transport");
            String name = Objects.requireNonNull(transport.name(), "transport name");
            if (transports.containsKey(name)) {
                throw new IllegalArgumentException("a transport named '" + name + "' was already added");
            }
            transports.put(name, transport);
            return this;
        }

        /**
         * Sets the default route; without one, every transport in the order added.
         *
         * @param names transport names, in order
         * @return this builder
         */
        public Builder defaultRoute(String... names) {
            this.defaultRoute = new ArrayList<String>(Arrays.asList(Objects.requireNonNull(names, "names")));
            return this;
        }

        /**
         * Sets the backoff before retrying an unavailable transport; each wait is jittered from half to one and a
         * half times this.
         *
         * @param retryBackoff the backoff; 250 ms by default
         * @return this builder
         */
        public Builder retryBackoff(Duration retryBackoff) {
            this.retryBackoff = nonNegative(retryBackoff, "retryBackoff");
            return this;
        }

        /**
         * Sets the longest retry-after the router waits for inline before trying the same transport again.
         *
         * @param maxInlineWait the limit; zero (never wait) by default
         * @return this builder
         */
        public Builder maxInlineWait(Duration maxInlineWait) {
            this.maxInlineWait = nonNegative(maxInlineWait, "maxInlineWait");
            return this;
        }

        /**
         * Sets whether an unclear outcome fails over to the next transport, accepting the risk of a duplicate.
         *
         * @param failover true to fail over; false by default
         * @return this builder
         */
        public Builder failoverOnUnknownOutcome(boolean failover) {
            this.failoverOnUnknownOutcome = failover;
            return this;
        }

        /**
         * Sets the executor handed to transports for asynchronous work.
         *
         * @param executor the executor; the calling thread by default
         * @return this builder
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Sets the clock for attempt times and send contexts.
         *
         * @param clock the clock; system UTC by default
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Replaces how the router waits between attempts, for tests.
         *
         * @param sleeper the sleeper
         * @return this builder
         */
        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
            return this;
        }

        /**
         * Builds the router.
         *
         * @return the router
         * @throws IllegalStateException    if no transport was added
         * @throws IllegalArgumentException if the default route names an unknown transport or repeats one
         */
        public Router build() {
            if (transports.isEmpty()) {
                throw new IllegalStateException("a router needs at least one transport");
            }
            List<String> route = defaultRoute != null ? defaultRoute : new ArrayList<String>(transports.keySet());
            checkRoute(route, transports);
            return new Router(this, new ArrayList<String>(route));
        }

        private static Duration nonNegative(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return duration;
        }
    }
}
