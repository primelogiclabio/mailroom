package io.primelogiclab.mailroom.internal;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.InvalidEmailException;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.MailroomException;
import io.primelogiclab.mailroom.MailroomListener;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.MailTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The {@link Mailroom} implementation: the router plus the default sender, the executor and closing. Not API: this
 * package may change in any release.
 */
public final class DefaultMailroom implements Mailroom {

    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final Router router;
    private final Address defaultFrom;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final AtomicBoolean closed;
    private final List<String> pinnedRoute;

    private DefaultMailroom(Router router, Address defaultFrom, Executor executor, ExecutorService ownedExecutor,
            AtomicBoolean closed, List<String> pinnedRoute) {
        this.router = router;
        this.defaultFrom = defaultFrom;
        this.executor = executor;
        this.ownedExecutor = ownedExecutor;
        this.closed = closed;
        this.pinnedRoute = pinnedRoute;
    }

    /**
     * Builds the mailroom; settings left null take the router's defaults.
     *
     * @param transports               the transports, with their final names
     * @param defaultRoute             the default route, or null for all in order
     * @param retryBackoff             the retry backoff, or null
     * @param maxInlineWait            the inline wait limit, or null
     * @param failoverOnUnknownOutcome whether unclear outcomes fail over
     * @param executor                 the caller's executor, or null to create the default one
     * @param clock                    the clock, or null
     * @param defaultFrom              the default sender, or null
     * @param listeners                the listeners, in the order to call them
     * @return the mailroom
     */
    public static Mailroom create(List<MailTransport> transports, String[] defaultRoute, Duration retryBackoff,
            Duration maxInlineWait, boolean failoverOnUnknownOutcome, Executor executor, Clock clock,
            Address defaultFrom, List<MailroomListener> listeners) {
        ExecutorService owned = null;
        Executor effective = executor;
        if (effective == null) {
            owned = defaultExecutor();
            effective = owned;
        }
        Router.Builder b = Router.builder().failoverOnUnknownOutcome(failoverOnUnknownOutcome).executor(effective);
        for (MailTransport transport : transports) {
            b.transport(transport);
        }
        for (MailroomListener listener : listeners) {
            b.listener(listener);
        }
        if (defaultRoute != null) {
            b.defaultRoute(defaultRoute);
        }
        if (retryBackoff != null) {
            b.retryBackoff(retryBackoff);
        }
        if (maxInlineWait != null) {
            b.maxInlineWait(maxInlineWait);
        }
        if (clock != null) {
            b.clock(clock);
        }
        Router router;
        try {
            router = b.build();
        } catch (RuntimeException e) {
            if (owned != null) {
                owned.shutdownNow();
            }
            throw e;
        }
        return new DefaultMailroom(router, defaultFrom, effective, owned, new AtomicBoolean(), null);
    }

    /**
     * Creates the default executor: one virtual thread per task.
     *
     * @return the executor
     */
    static ExecutorService defaultExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public SendResult send(Email email) {
        Objects.requireNonNull(email, "email");
        checkOpen();
        Email withSender = withSender(email);
        return pinnedRoute == null ? router.send(withSender) : router.send(withSender, pinnedRoute);
    }

    @Override
    public CompletableFuture<SendResult> sendAsync(final Email email) {
        Objects.requireNonNull(email, "email");
        checkOpen();
        return CompletableFuture.supplyAsync(new Supplier<SendResult>() {
            @Override
            public SendResult get() {
                return send(email);
            }
        }, executor);
    }

    @Override
    public Mailroom via(String... transportNames) {
        Objects.requireNonNull(transportNames, "transportNames");
        if (transportNames.length == 0) {
            throw new IllegalArgumentException("via needs at least one transport name");
        }
        Set<String> seen = new HashSet<>();
        for (String name : transportNames) {
            if (!router.transports().containsKey(Objects.requireNonNull(name, "transport name"))) {
                throw new IllegalArgumentException("no transport named '" + name + "' (configured: "
                        + router.transports().keySet() + ")");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("via names '" + name + "' twice");
            }
        }
        return new DefaultMailroom(router, defaultFrom, executor, null, closed,
                List.of(transportNames));
    }

    @Override
    public void close() {
        if (pinnedRoute != null || !closed.compareAndSet(false, true)) {
            return;
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
            try {
                if (!ownedExecutor.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    ownedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ownedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        RuntimeException first = null;
        for (MailTransport transport : router.transports().values()) {
            try {
                transport.close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = new MailroomException("Closing a transport failed", e);
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public String toString() {
        return "Mailroom" + (pinnedRoute == null ? router.defaultRoute() : pinnedRoute)
                + (closed.get() ? " (closed)" : "");
    }

    private Email withSender(Email email) {
        if (email.from().isPresent()) {
            return email;
        }
        if (defaultFrom == null) {
            throw new InvalidEmailException("no sender: set from on the email or defaultFrom on the Mailroom");
        }
        return email.toBuilder().from(defaultFrom).build();
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("This Mailroom is closed");
        }
    }
}
