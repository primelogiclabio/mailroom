package io.primelogiclab.mailroom.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.InvalidEmailException;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MailroomTest {

    private static Email email() {
        return Email.builder().to("player@example.com").subject("Hi").text("Hello").build();
    }

    @Test
    void fillsInTheDefaultSenderOnlyWhenTheEmailHasNone() {
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("Shop <shop@example.com>").transport(outbox).build()) {
            mailroom.send(email());
            mailroom.send(email().toBuilder().from("other@example.com").build());
        }
        assertThat(outbox.sent().get(0).from()).contains(Address.of("Shop", "shop@example.com"));
        assertThat(outbox.sent().get(1).from()).contains(Address.of("other@example.com"));
    }

    @Test
    void aMissingSenderIsAnInvalidEmail() {
        try (Mailroom mailroom = Mailroom.builder().transport(InMemoryTransport.named("primary")).build()) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(InvalidEmailException.class)
                    .hasMessageContaining("no sender");
        }
    }

    @Test
    void failsOverThroughTheFacadeWithEveryAttempt() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.REJECTED).build();
        InMemoryTransport backup = InMemoryTransport.named("backup");
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(primary)
                .transport(backup).build()) {
            SendResult result = mailroom.send(email());
            assertThat(result.transportName()).isEqualTo("backup");
            assertThat(result.attempts()).hasSize(2);
        }
    }

    @Test
    void defaultRouteAndViaChooseTheTransports() {
        InMemoryTransport a = InMemoryTransport.named("a");
        InMemoryTransport b = InMemoryTransport.named("b");
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(a).transport(b)
                .defaultRoute("b", "a").build()) {
            assertThat(mailroom.send(email()).transportName()).isEqualTo("b");
            assertThat(mailroom.via("a").send(email()).transportName()).isEqualTo("a");
            assertThat(mailroom.via("a").via("b").send(email()).transportName()).isEqualTo("b");
            assertThatThrownBy(() -> mailroom.via("nope")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no transport named 'nope' (configured: [a, b])");
            assertThatThrownBy(() -> mailroom.via()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> mailroom.via("a", "a")).isInstanceOf(IllegalArgumentException.class);
        }
        a.assertSentCount(1);
        b.assertSentCount(2);
    }

    @Test
    void aTransportAddedUnderAnotherNameUsesThatNameEverywhere() {
        ScriptedTransport scripted = ScriptedTransport.builder("scripted-1").fail(FailureKind.UNAVAILABLE)
                .fail(FailureKind.UNAVAILABLE).build();
        InMemoryTransport backup = InMemoryTransport.named("in-memory");
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport("primary", scripted)
                .transport("backup", backup).retryBackoff(Duration.ZERO).build()) {
            SendResult result = mailroom.send(email());
            assertThat(result.transportName()).isEqualTo("backup");
            assertThat(result.attempts()).extracting(a -> a.transportName())
                    .containsExactly("primary", "primary", "backup");
            assertThat(mailroom.via("primary").send(email()).transportName()).isEqualTo("primary");
        }
        ScriptedTransport down = ScriptedTransport.builder("x").thenAlwaysFail(FailureKind.AUTHENTICATION).build();
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport("primary", down)
                .build()) {
            assertThatThrownBy(() -> mailroom.send(email()))
                    .isInstanceOfSatisfying(SendFailedException.class,
                            e -> assertThat(e.transportName()).contains("primary"));
        }
    }

    @Test
    void sendAsyncCompletesWithTheResultOrTheFailure() throws Exception {
        ScriptedTransport t = ScriptedTransport.builder("primary").fail(FailureKind.INVALID_MESSAGE).build();
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(t).build()) {
            CompletableFuture<SendResult> failed = mailroom.sendAsync(email());
            assertThatThrownBy(() -> failed.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SendFailedException.class);
            SendResult ok = mailroom.sendAsync(email()).get(10, TimeUnit.SECONDS);
            assertThat(ok.transportName()).isEqualTo("primary");
        }
    }

    @Test
    void usesTheGivenClock() {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(outbox)
                .clock(Clock.fixed(now, ZoneOffset.UTC)).build()) {
            assertThat(mailroom.send(email()).acceptedAt()).isEqualTo(now);
        }
    }

    @Test
    void closeClosesTransportsAndRejectsLaterSends() {
        final boolean[] closed = {false};
        ScriptedTransport plain = ScriptedTransport.builder("primary").build();
        io.primelogiclab.mailroom.spi.MailTransport closing = new io.primelogiclab.mailroom.spi.MailTransport() {
            @Override
            public String name() {
                return "closing";
            }

            @Override
            public String providerId() {
                return "test";
            }

            @Override
            public io.primelogiclab.mailroom.spi.TransportCapabilities capabilities() {
                return plain.capabilities();
            }

            @Override
            public SendResult send(Email email, io.primelogiclab.mailroom.spi.SendContext context) {
                return plain.send(email, context);
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(closing).build();
        Mailroom pinned = mailroom.via("closing");
        pinned.close();
        assertThat(closed[0]).as("closing a pinned view does nothing").isFalse();
        pinned.send(email());

        mailroom.close();
        assertThat(closed[0]).isTrue();
        assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mailroom.sendAsync(email())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pinned.send(email())).isInstanceOf(IllegalStateException.class);
        mailroom.close();
        assertThat(mailroom.toString()).isEqualTo("Mailroom[closing] (closed)");
    }

    @Test
    void neverShutsDownAnExecutorYouSupply() throws Exception {
        ExecutorService mine = Executors.newSingleThreadExecutor();
        try {
            Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com")
                    .transport(InMemoryTransport.named("primary")).executor(mine).build();
            mailroom.sendAsync(email()).get(10, TimeUnit.SECONDS);
            mailroom.close();
            assertThat(mine.isShutdown()).isFalse();
        } finally {
            mine.shutdownNow();
        }
    }

    @Test
    void rejectsBadBuilds() {
        assertThatThrownBy(() -> Mailroom.builder().build()).isInstanceOf(IllegalStateException.class)
                .hasMessage("A Mailroom needs at least one transport");
        assertThatThrownBy(() -> Mailroom.builder().transport(InMemoryTransport.named("a"))
                .transport(InMemoryTransport.named("a")).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mailroom.builder().transport(InMemoryTransport.named("a")).defaultRoute("b").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mailroom.builder().defaultFrom("not an address"))
                .isInstanceOf(InvalidEmailException.class);
    }
}
