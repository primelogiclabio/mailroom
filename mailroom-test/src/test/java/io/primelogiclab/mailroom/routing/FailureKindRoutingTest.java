package io.primelogiclab.mailroom.routing;

import static io.primelogiclab.mailroom.routing.Routing.describe;
import static io.primelogiclab.mailroom.routing.Routing.email;
import static io.primelogiclab.mailroom.routing.Routing.failure;
import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.internal.Router;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** One test per row of the design's FailureKind table. */
class FailureKindRoutingTest {

    private final Routing.RecordingSleeper sleeper = new Routing.RecordingSleeper();

    private Router.Builder router(ScriptedTransport... transports) {
        Router.Builder b = Router.builder().sleeper(sleeper);
        for (ScriptedTransport t : transports) {
            b.transport(t);
        }
        return b;
    }

    private static ScriptedTransport ok(String name) {
        return ScriptedTransport.builder(name).build();
    }

    @Test
    void invalidMessageStopsWithoutRetryOrFailover() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.INVALID_MESSAGE, 400).build();
        ScriptedTransport backup = ok("backup");
        SendFailedException e = failure(() -> router(primary, backup).build().send(email()));
        assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE);
        assertThat(e.attempts()).hasSize(1);
        backup.assertCalls(0);
        assertThat(sleeper.waits).isEmpty();
    }

    @Test
    void rejectedAndAuthenticationFailOverWithoutRetry() {
        for (FailureKind kind : new FailureKind[] {FailureKind.REJECTED, FailureKind.AUTHENTICATION}) {
            ScriptedTransport primary = ScriptedTransport.builder("primary").fail(kind).build();
            ScriptedTransport backup = ok("backup");
            SendResult r = router(primary, backup).build().send(email());
            assertThat(describe(r.attempts())).containsExactly("primary " + kind.name(), "backup SUCCEEDED");
            primary.assertCalls(1);
        }
        assertThat(sleeper.waits).isEmpty();
    }

    @Test
    void rateLimitedWaitsInlineWhenTheRetryAfterIsShortEnough() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").rateLimited(Duration.ofMillis(800)).build();
        ScriptedTransport backup = ok("backup");
        SendResult r = router(primary, backup).maxInlineWait(Duration.ofSeconds(1)).build().send(email());
        assertThat(describe(r.attempts())).containsExactly("primary RATE_LIMITED", "primary SUCCEEDED");
        assertThat(sleeper.waits).containsExactly(Duration.ofMillis(800));
        backup.assertCalls(0);
    }

    @Test
    void rateLimitedFailsOverByDefault() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").rateLimited(Duration.ofSeconds(1)).build();
        ScriptedTransport backup = ok("backup");
        SendResult r = router(primary, backup).build().send(email());
        assertThat(describe(r.attempts())).containsExactly("primary RATE_LIMITED", "backup SUCCEEDED");
        assertThat(sleeper.waits).isEmpty();
    }

    @Test
    void rateLimitedWithoutRetryAfterFailsOver() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.RATE_LIMITED, 429).build();
        SendResult r = router(primary, ok("backup")).maxInlineWait(Duration.ofSeconds(5)).build().send(email());
        assertThat(describe(r.attempts())).containsExactly("primary RATE_LIMITED", "backup SUCCEEDED");
    }

    @Test
    void unavailableIsRetriedExactlyOnce() {
        ScriptedTransport flaky = ScriptedTransport.builder("primary").fail(FailureKind.UNAVAILABLE).build();
        SendResult r = router(flaky).build().send(email());
        assertThat(describe(r.attempts())).containsExactly("primary UNAVAILABLE", "primary SUCCEEDED");
        assertThat(sleeper.waits).hasSize(1);
    }

    @Test
    void theBackoffIsConfigurableAndJittered() {
        ScriptedTransport flaky = ScriptedTransport.builder("primary").thenAlwaysFail(FailureKind.UNAVAILABLE).build();
        Router r = router(flaky).retryBackoff(Duration.ofSeconds(2)).build();
        for (int i = 0; i < 20; i++) {
            failure(() -> r.send(email()));
        }
        assertThat(sleeper.waits).hasSize(20)
                .allSatisfy(d -> assertThat(d).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3)));
        assertThat(sleeper.waits).doesNotHaveDuplicates();
    }

    @Test
    void whenEveryTransportFailsTheLastFailureComesBackWithEveryAttempt() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").thenAlwaysFail(FailureKind.UNAVAILABLE).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").fail(FailureKind.AUTHENTICATION, 401).build();
        SendFailedException e = failure(() -> router(primary, backup).build().send(email()));
        assertThat(e.kind()).isEqualTo(FailureKind.AUTHENTICATION);
        assertThat(e.transportName()).contains("backup");
        assertThat(describe(e.attempts()))
                .containsExactly("primary UNAVAILABLE", "primary UNAVAILABLE", "backup AUTHENTICATION");
    }

    @Test
    void aTransportBugCountsAsUnknownOutcome() {
        MailTransport buggy = new MailTransport() {
            @Override
            public String name() {
                return "buggy";
            }

            @Override
            public String providerId() {
                return "broken";
            }

            @Override
            public TransportCapabilities capabilities() {
                return ScriptedTransport.builder("x").build().capabilities();
            }

            @Override
            public SendResult send(Email email, SendContext context) {
                throw new IllegalStateException("boom for player@example.com");
            }
        };
        ScriptedTransport backup = ok("backup");
        Router r = Router.builder().transport(buggy).transport(backup).sleeper(sleeper).build();
        SendFailedException e = failure(() -> r.send(email()));
        assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(e.getMessage()).isEqualTo("UNKNOWN_OUTCOME via buggy: IllegalStateException from transport");
        assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        backup.assertCalls(0);
    }

    @Test
    void anInterruptedWaitReturnsTheFailureAndKeepsTheInterrupt() {
        ScriptedTransport flaky = ScriptedTransport.builder("primary").fail(FailureKind.UNAVAILABLE).build();
        ScriptedTransport backup = ok("backup");
        Router r = Router.builder().transport(flaky).transport(backup).sleeper(new Router.Sleeper() {
            @Override
            public void sleep(Duration duration) throws InterruptedException {
                throw new InterruptedException();
            }
        }).build();
        try {
            SendFailedException e = failure(() -> r.send(email()));
            assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE);
            assertThat(e.attempts()).hasSize(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            backup.assertCalls(0);
        } finally {
            Thread.interrupted();
        }
    }
}
