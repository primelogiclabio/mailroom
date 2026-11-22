package io.primelogiclab.mailroom.routing;

import static io.primelogiclab.mailroom.routing.Routing.email;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.internal.Router;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RouterTest {

    @Test
    void theDefaultRouteIsEveryTransportInOrder() {
        InMemoryTransport a = InMemoryTransport.named("a");
        InMemoryTransport b = InMemoryTransport.named("b");
        Router router = Router.builder().transport(a).transport(b).build();
        assertThat(router.defaultRoute()).containsExactly("a", "b");
        assertThat(router.transports()).containsOnlyKeys("a", "b");
        assertThat(router.send(email()).transportName()).isEqualTo("a");
        b.assertNothingSent();
    }

    @Test
    void aConfiguredDefaultRouteWins() {
        InMemoryTransport a = InMemoryTransport.named("a");
        InMemoryTransport b = InMemoryTransport.named("b");
        Router router = Router.builder().transport(a).transport(b).defaultRoute("b", "a").build();
        assertThat(router.send(email()).transportName()).isEqualTo("b");
    }

    @Test
    void aPinnedRouteUsesOnlyTheNamedTransports() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").build();
        InMemoryTransport internal = InMemoryTransport.named("internal");
        Router router = Router.builder().transport(primary).transport(internal).build();
        SendResult r = router.send(email(), Collections.singletonList("internal"));
        assertThat(r.transportName()).isEqualTo("internal");
        primary.assertCalls(0);
    }

    @Test
    void rejectsBadConfigurationAndRoutes() {
        InMemoryTransport a = InMemoryTransport.named("a");
        assertThatThrownBy(() -> Router.builder().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Router.builder().transport(a).transport(InMemoryTransport.named("a")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already added");
        assertThatThrownBy(() -> Router.builder().transport(a).defaultRoute("a", "missing").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no transport named 'missing' (configured: a)");
        assertThatThrownBy(() -> Router.builder().transport(a).defaultRoute("a", "a").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        Router router = Router.builder().transport(a).build();
        assertThatThrownBy(() -> router.send(email(), Collections.<String>emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.send(email(), Arrays.asList("nope")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Router.builder().transport(a).retryBackoff(java.time.Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachTryGetsTheConfiguredExecutorAndClock() {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Executor executor = Executors.newSingleThreadExecutor();
        try {
            ScriptedTransport t = ScriptedTransport.builder("t").build();
            SendResult r = Router.builder().transport(t).executor(executor).clock(clock).build().send(email());
            SendContext context = t.contexts().get(0);
            assertThat(context.executor()).isSameAs(executor);
            assertThat(context.clock()).isSameAs(clock);
            assertThat(r.acceptedAt()).isEqualTo(now);
            assertThat(r.attempts().get(0).startedAt()).isEqualTo(now);
        } finally {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    @Test
    void handlesConcurrentSends() throws Exception {
        final InMemoryTransport outbox = InMemoryTransport.named("primary");
        final Router router = Router.builder().transport(outbox).build();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<SendResult>> futures = new ArrayList<Future<SendResult>>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> router.send(email())));
            }
            for (Future<SendResult> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS).attempts()).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
        outbox.assertSentCount(100);
    }
}
