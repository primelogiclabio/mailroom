package io.primelogiclab.mailroom.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Attempt;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.MailroomBuilder;
import io.primelogiclab.mailroom.MailroomException;
import io.primelogiclab.mailroom.MailroomListener;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.internal.Router;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ListenerTest {

    /** Writes each event as one line, such as "attempt primary UNAVAILABLE" or "success backup". */
    static final class Recorder implements MailroomListener {
        final List<String> events;
        final String prefix;
        volatile MailroomException failure;

        Recorder() {
            this(new CopyOnWriteArrayList<String>(), "");
        }

        Recorder(List<String> events, String prefix) {
            this.events = events;
            this.prefix = prefix;
        }

        @Override
        public void onSkip(Email email, String transportName, String reason) {
            events.add(prefix + "skip " + transportName + ": " + reason);
        }

        @Override
        public void onAttempt(Email email, Attempt attempt) {
            events.add(prefix + "attempt " + attempt.transportName() + " "
                    + (attempt.succeeded() ? "SUCCEEDED" : attempt.failureKind().get().name()));
        }

        @Override
        public void onSuccess(Email email, SendResult result) {
            events.add(prefix + "success " + result.transportName());
        }

        @Override
        public void onFailure(Email email, MailroomException failure) {
            this.failure = failure;
            events.add(prefix + "failure " + (failure instanceof SendFailedException
                    ? ((SendFailedException) failure).kind().name() : failure.getClass().getSimpleName()));
        }
    }

    /** Throws from every event. */
    static final class Throwing implements MailroomListener {
        @Override
        public void onSkip(Email email, String transportName, String reason) {
            throw new IllegalStateException("skip");
        }

        @Override
        public void onAttempt(Email email, Attempt attempt) {
            throw new IllegalStateException("attempt");
        }

        @Override
        public void onSuccess(Email email, SendResult result) {
            throw new IllegalStateException("success");
        }

        @Override
        public void onFailure(Email email, MailroomException failure) {
            throw new IllegalStateException("failure");
        }
    }

    static Email email() {
        return Email.builder().from("shop@example.com").to("player@example.com").subject("Hi").text("Hello").build();
    }

    private static MailroomBuilder builder(ScriptedTransport... transports) {
        MailroomBuilder b = Mailroom.builder().retryBackoff(Duration.ZERO);
        for (ScriptedTransport transport : transports) {
            b.transport(transport);
        }
        return b;
    }

    @Test
    void reportsEachAttemptThenTheSuccess() {
        Recorder recorder = new Recorder();
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.UNAVAILABLE).build();
        try (Mailroom mailroom = builder(primary).listener(recorder).build()) {
            mailroom.send(email());
        }
        assertThat(recorder.events).containsExactly(
                "attempt primary UNAVAILABLE", "attempt primary SUCCEEDED", "success primary");
    }

    @Test
    void reportsFailover() {
        Recorder recorder = new Recorder();
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.REJECTED).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        try (Mailroom mailroom = builder(primary, backup).listener(recorder).build()) {
            mailroom.send(email());
        }
        assertThat(recorder.events).containsExactly(
                "attempt primary REJECTED", "attempt backup SUCCEEDED", "success backup");
    }

    @Test
    void reportsSkipsBeforeAttempts() {
        Recorder recorder = new Recorder();
        ScriptedTransport primary = ScriptedTransport.builder("primary").rejectPrecheck("too large").build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        try (Mailroom mailroom = builder(primary, backup).listener(recorder).build()) {
            mailroom.send(email());
        }
        assertThat(recorder.events).containsExactly(
                "skip primary: too large", "attempt backup SUCCEEDED", "success backup");
    }

    @Test
    void reportsNoEligibleTransportAsTheFailureTheCallerGets() {
        Recorder recorder = new Recorder();
        ScriptedTransport a = ScriptedTransport.builder("a").rejectPrecheck("too large").build();
        ScriptedTransport b = ScriptedTransport.builder("b").rejectPrecheck("too many recipients").build();
        try (Mailroom mailroom = builder(a, b).listener(recorder).build()) {
            assertThatThrownBy(() -> mailroom.send(email()))
                    .isInstanceOf(NoEligibleTransportException.class)
                    .satisfies(thrown -> assertThat(thrown).isSameAs(recorder.failure));
        }
        assertThat(recorder.events).containsExactly(
                "skip a: too large", "skip b: too many recipients", "failure NoEligibleTransportException");
    }

    @Test
    void reportsAStopWithEveryAttempt() {
        Recorder recorder = new Recorder();
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.INVALID_MESSAGE).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        try (Mailroom mailroom = builder(primary, backup).listener(recorder).build()) {
            assertThatThrownBy(() -> mailroom.send(email()))
                    .isInstanceOfSatisfying(SendFailedException.class, e -> {
                        assertThat(e).isSameAs(recorder.failure);
                        assertThat(e.attempts()).hasSize(1);
                    });
        }
        assertThat(recorder.events).containsExactly("attempt primary INVALID_MESSAGE", "failure INVALID_MESSAGE");
        backup.assertCalls(0);
    }

    @Test
    void reportsTheFailureWhenEveryTransportFails() {
        Recorder recorder = new Recorder();
        ScriptedTransport primary = ScriptedTransport.builder("primary").thenAlwaysFail(FailureKind.UNAVAILABLE)
                .build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").thenAlwaysFail(FailureKind.REJECTED).build();
        try (Mailroom mailroom = builder(primary, backup).listener(recorder).build()) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(SendFailedException.class);
        }
        assertThat(recorder.events).containsExactly("attempt primary UNAVAILABLE", "attempt primary UNAVAILABLE",
                "attempt backup REJECTED", "failure REJECTED");
    }

    @Test
    void reportsTheFailureWhenARetryWaitIsInterrupted() {
        Recorder recorder = new Recorder();
        Router router = Router.builder()
                .transport(ScriptedTransport.builder("primary").fail(FailureKind.UNAVAILABLE).build())
                .sleeper(duration -> {
                    throw new InterruptedException();
                })
                .listener(recorder)
                .build();
        try {
            assertThatThrownBy(() -> router.send(email())).isInstanceOf(SendFailedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(recorder.events).containsExactly("attempt primary UNAVAILABLE", "failure UNAVAILABLE");
    }

    @Test
    void callsListenersInTheOrderAdded() {
        List<String> events = new CopyOnWriteArrayList<String>();
        try (Mailroom mailroom = builder(ScriptedTransport.builder("primary").build())
                .listener(new Recorder(events, "first "))
                .listener(new Recorder(events, "second "))
                .build()) {
            mailroom.send(email());
        }
        assertThat(events).containsExactly("first attempt primary SUCCEEDED", "second attempt primary SUCCEEDED",
                "first success primary", "second success primary");
    }

    @Test
    void aThrowingListenerChangesNothing() {
        Recorder recorder = new Recorder();
        ScriptedTransport primary = ScriptedTransport.builder("primary").rejectPrecheck("too large").build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        try (Mailroom mailroom = builder(primary, backup).listener(new Throwing()).listener(recorder).build()) {
            SendResult result = mailroom.send(email());
            assertThat(result.transportName()).isEqualTo("backup");
        }
        assertThat(recorder.events).containsExactly(
                "skip primary: too large", "attempt backup SUCCEEDED", "success backup");
        String name = Throwing.class.getName();
        assertThat(LogLeakGuard.lines()).contains(
                "WARNING listener " + name + " threw java.lang.IllegalStateException in onSkip; ignored",
                "WARNING listener " + name + " threw java.lang.IllegalStateException in onAttempt; ignored",
                "WARNING listener " + name + " threw java.lang.IllegalStateException in onSuccess; ignored");
    }

    @Test
    void aThrowingListenerDoesNotHideTheFailure() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.INVALID_MESSAGE).build();
        try (Mailroom mailroom = builder(primary).listener(new Throwing()).build()) {
            assertThatThrownBy(() -> mailroom.send(email()))
                    .isInstanceOfSatisfying(SendFailedException.class,
                            e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE));
        }
        assertThat(LogLeakGuard.lines()).contains("WARNING listener " + Throwing.class.getName()
                + " threw java.lang.IllegalStateException in onFailure; ignored");
    }

    @Test
    void aPinnedViewKeepsTheListeners() {
        Recorder recorder = new Recorder();
        try (Mailroom mailroom = builder(ScriptedTransport.builder("primary").build(),
                ScriptedTransport.builder("backup").build()).listener(recorder).build()) {
            mailroom.via("backup").send(email());
        }
        assertThat(recorder.events).containsExactly("attempt backup SUCCEEDED", "success backup");
    }

    @Test
    void reportsAsyncSends() {
        Recorder recorder = new Recorder();
        try (Mailroom mailroom = builder(ScriptedTransport.builder("primary").build()).listener(recorder).build()) {
            mailroom.sendAsync(email()).join();
        }
        assertThat(recorder.events).containsExactly("attempt primary SUCCEEDED", "success primary");
    }

    @Test
    void aListenerOverridesOnlyWhatItNeeds() {
        List<String> successes = new CopyOnWriteArrayList<String>();
        MailroomListener onlySuccess = new MailroomListener() {
            @Override
            public void onSuccess(Email email, SendResult result) {
                successes.add(result.transportName());
            }
        };
        ScriptedTransport primary = ScriptedTransport.builder("primary").fail(FailureKind.UNAVAILABLE).build();
        try (Mailroom mailroom = builder(primary).listener(onlySuccess).build()) {
            mailroom.send(email());
        }
        assertThat(successes).containsExactly("primary");
    }
}
