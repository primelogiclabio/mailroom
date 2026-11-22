package io.primelogiclab.mailroom.test;

import static io.primelogiclab.mailroom.test.InMemoryTransportTest.CONTEXT;
import static io.primelogiclab.mailroom.test.InMemoryTransportTest.email;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.BatchItemResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ScriptedTransportTest {

    private static final Email EMAIL = email("player@example.com");

    private static SendFailedException failureOf(ScriptedTransport transport) {
        try {
            transport.send(EMAIL, CONTEXT);
        } catch (SendFailedException e) {
            return e;
        }
        throw new AssertionError("expected the step to fail");
    }

    @Test
    void withoutAScriptEveryCallSucceeds() {
        ScriptedTransport t = ScriptedTransport.builder("primary").build();
        assertThat(t.send(EMAIL, CONTEXT).providerMessageId()).contains("scripted-1");
        assertThat(t.send(EMAIL, CONTEXT).providerMessageId()).contains("scripted-2");
        assertThat(t.providerId()).isEqualTo("scripted");
    }

    @Test
    void runsTheStepsInOrderThenSucceeds() {
        ScriptedTransport t = ScriptedTransport.builder("primary")
                .fail(FailureKind.UNAVAILABLE, 503)
                .fail(FailureKind.AUTHENTICATION)
                .succeed()
                .rateLimited(Duration.ofSeconds(2))
                .build();
        SendFailedException first = failureOf(t);
        assertThat(first.kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(first.statusCode()).hasValue(503);
        assertThat(first.transportName()).contains("primary");
        assertThat(first.providerId()).contains("scripted");
        assertThat(first.getMessage()).isEqualTo("UNAVAILABLE via primary: scripted UNAVAILABLE");

        assertThat(failureOf(t).kind()).isEqualTo(FailureKind.AUTHENTICATION);
        assertThat(t.send(EMAIL, CONTEXT).transportName()).isEqualTo("primary");

        SendFailedException limited = failureOf(t);
        assertThat(limited.kind()).isEqualTo(FailureKind.RATE_LIMITED);
        assertThat(limited.statusCode()).hasValue(429);
        assertThat(limited.retryAfter()).contains(Duration.ofSeconds(2));

        assertThat(t.send(EMAIL, CONTEXT)).isNotNull();
        t.assertCalls(5);
    }

    @Test
    void canFailForeverAfterTheScript() {
        ScriptedTransport t = ScriptedTransport.builder("down").succeed().thenAlwaysFail(FailureKind.UNAVAILABLE)
                .build();
        t.send(EMAIL, CONTEXT);
        assertThat(failureOf(t).kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(failureOf(t).kind()).isEqualTo(FailureKind.UNAVAILABLE);
        ScriptedTransport back = ScriptedTransport.builder("x").thenAlwaysFail(FailureKind.REJECTED)
                .thenAlwaysSucceed().build();
        assertThat(back.send(EMAIL, CONTEXT)).isNotNull();
    }

    @Test
    void throwsAGivenExceptionAsIs() {
        SendFailedException given = SendFailedException.builder(FailureKind.REJECTED, "sender not verified")
                .transportName("primary").providerCode("unverified_sender").build();
        ScriptedTransport t = ScriptedTransport.builder("primary").fail(given).build();
        assertThat(failureOf(t)).isSameAs(given);
    }

    @Test
    void waitsBeforeTheOutcome() {
        ScriptedTransport t = ScriptedTransport.builder("slow")
                .succeedAfter(Duration.ofMillis(60))
                .failAfter(Duration.ofMillis(60), FailureKind.UNKNOWN_OUTCOME)
                .build();
        long start = System.nanoTime();
        t.send(EMAIL, CONTEXT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        start = System.nanoTime();
        assertThat(failureOf(t).kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void anInterruptedDelayIsAnUnknownOutcome() {
        ScriptedTransport t = ScriptedTransport.builder("slow").succeedAfter(Duration.ofSeconds(5)).build();
        Thread.currentThread().interrupt();
        try {
            SendFailedException e = failureOf(t);
            assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void recordsEveryCallAndCanStartOver() {
        ScriptedTransport t = ScriptedTransport.builder("primary").fail(FailureKind.UNAVAILABLE).build();
        SendContext second = SendContext.builder().attempt(2).build();
        failureOf(t);
        t.send(email("b@example.com"), second);
        assertThat(t.calls()).isEqualTo(2);
        assertThat(t.received()).extracting(e -> e.to().get(0).address())
                .containsExactly("player@example.com", "b@example.com");
        assertThat(t.contexts()).extracting(SendContext::attempt).containsExactly(1, 2);
        assertThat(t.toString()).isEqualTo("ScriptedTransport[primary, steps=1, calls=2]");
        assertThatThrownBy(() -> t.assertCalls(3)).isInstanceOf(AssertionError.class)
                .hasMessage("Expected 3 call(s) to 'primary' but there were 2");

        t.reset();
        t.assertCalls(0);
        assertThat(failureOf(t).kind()).isEqualTo(FailureKind.UNAVAILABLE);
    }

    @Test
    void canRejectEveryPrecheck() {
        assertThat(ScriptedTransport.builder("t").build().precheck(EMAIL).passed()).isTrue();
        assertThat(ScriptedTransport.builder("t").rejectPrecheck("too large").build().precheck(EMAIL).reason())
                .contains("too large");
    }

    @Test
    void hasConfigurableCapabilities() {
        ScriptedTransport t = ScriptedTransport.builder("t").providerId("fake-resend")
                .capabilities(TransportCapabilities.builder().supports(Capability.TEXT).build())
                .understands(InMemoryTransportTest.TemplateOptions.class).build();
        assertThat(t.providerId()).isEqualTo("fake-resend");
        assertThat(t.capabilities().supported()).containsExactly(Capability.TEXT);
        assertThat(t.capabilities().understands(InMemoryTransportTest.TemplateOptions.class)).isTrue();
    }

    @Test
    void worksWithTheSpiDefaultsForAsyncAndBatch() {
        ScriptedTransport t = ScriptedTransport.builder("t").succeed().fail(FailureKind.REJECTED).succeed().build();
        List<BatchItemResult> batch = t.sendBatch(Arrays.asList(EMAIL, EMAIL, EMAIL), CONTEXT);
        assertThat(batch).extracting(BatchItemResult::succeeded).containsExactly(true, false, true);

        ScriptedTransport async = ScriptedTransport.builder("a").fail(FailureKind.UNAVAILABLE).build();
        assertThatThrownBy(() -> async.sendAsync(EMAIL, CONTEXT).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(SendFailedException.class);
        SendResult ok = async.sendAsync(EMAIL, CONTEXT).toCompletableFuture().join();
        assertThat(ok.providerMessageId()).contains("scripted-1");
    }

    @Test
    void rejectsBadSteps() {
        assertThatThrownBy(() -> ScriptedTransport.builder("t").fail(FailureKind.UNAVAILABLE, 42))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScriptedTransport.builder("t").succeedAfter(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScriptedTransport.builder("t").fail((FailureKind) null))
                .isInstanceOf(NullPointerException.class);
    }
}
