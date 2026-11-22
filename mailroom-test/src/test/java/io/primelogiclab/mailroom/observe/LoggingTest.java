package io.primelogiclab.mailroom.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.MailroomBuilder;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.TransportRegistry;
import io.primelogiclab.mailroom.internal.Observer;
import io.primelogiclab.mailroom.spi.ConfigKey;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.spi.TransportConfig;
import io.primelogiclab.mailroom.spi.TransportProvider;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/** What mailroom logs, and that it never logs an address or a secret. {@link LogLeakGuard} checks every line too. */
class LoggingTest {

    private static Email email() {
        return ListenerTest.email();
    }

    private static Mailroom mailroom(MailTransport... transports) {
        MailroomBuilder b = Mailroom.builder().retryBackoff(Duration.ZERO);
        for (MailTransport transport : transports) {
            b.transport(transport);
        }
        return b.build();
    }

    @Test
    void logsASuccessAtInfoWithTransportAttemptsAndMessageId() {
        try (Mailroom mailroom = mailroom(
                ScriptedTransport.builder("primary").providerId("sendgrid").fail(FailureKind.UNAVAILABLE).build())) {
            mailroom.send(email());
        }
        List<String> lines = LogLeakGuard.lines();
        assertThat(lines).filteredOn(line -> line.startsWith("INFO "))
                .containsExactly("INFO sent via primary (sendgrid) after 2 attempts, messageId scripted-1");
        assertThat(lines).filteredOn(line -> line.startsWith("FINE attempt 2 "))
                .singleElement().asString().startsWith("FINE attempt 2 on primary (sendgrid) succeeded in ");
        for (LogRecord record : LogLeakGuard.records()) {
            assertThat(record.getLoggerName()).isEqualTo(Observer.LOGGER_NAME).isEqualTo("io.primelogiclab.mailroom");
        }
    }

    @Test
    void logsEachFailedAttemptAtFineWithWhatHappensNext() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").providerId("sendgrid")
                .thenAlwaysFail(FailureKind.UNAVAILABLE).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").providerId("smtp2go")
                .fail(FailureKind.REJECTED, 550).build();
        try (Mailroom mailroom = mailroom(primary, backup)) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(SendFailedException.class);
        }
        List<String> fine = LogLeakGuard.lines();
        assertThat(fine).filteredOn(line -> line.startsWith("FINE attempt")).hasSize(3).satisfiesExactly(
                line -> assertThat(line).startsWith("FINE attempt 1 on primary (sendgrid) failed after ")
                        .contains(": UNAVAILABLE via primary: ").endsWith("; retrying in 0 ms"),
                line -> assertThat(line).startsWith("FINE attempt 2 on primary (sendgrid) failed after ")
                        .endsWith("; trying the next transport"),
                line -> assertThat(line).startsWith("FINE attempt 1 on backup (smtp2go) failed after ")
                        .contains(": REJECTED via backup: ").contains(" (status 550)")
                        .endsWith("; no transports left"));
        assertThat(fine).filteredOn(line -> line.startsWith("WARNING "))
                .containsExactly("WARNING send failed: REJECTED after 3 attempts (primary, backup)");
    }

    @Test
    void logsAStop() {
        try (Mailroom mailroom = mailroom(
                ScriptedTransport.builder("primary").fail(FailureKind.UNKNOWN_OUTCOME).build(),
                ScriptedTransport.builder("backup").build())) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(SendFailedException.class);
        }
        assertThat(LogLeakGuard.lines())
                .anySatisfy(line -> assertThat(line).startsWith("FINE attempt 1 on primary").endsWith("; stopping"))
                .contains("WARNING send failed: UNKNOWN_OUTCOME after 1 attempt (primary)");
    }

    @Test
    void masksAddressesInSkipReasons() {
        ScriptedTransport a = ScriptedTransport.builder("a")
                .rejectPrecheck("player@example.com is on the suppression list").build();
        ScriptedTransport b = ScriptedTransport.builder("b").rejectPrecheck("doesn't take 'quoted' reasons").build();
        try (Mailroom mailroom = mailroom(a, b)) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(NoEligibleTransportException.class);
        }
        assertThat(LogLeakGuard.lines()).containsExactly(
                "FINE skipped a: p***@example.com is on the suppression list",
                "FINE skipped b: doesn't take 'quoted' reasons",
                "WARNING no eligible transport: a p***@example.com is on the suppression list; "
                        + "b doesn't take 'quoted' reasons");
    }

    @Test
    void masksAddressesInFailureMessagesAndNeverAttachesTheCause() {
        SendFailedException failure = SendFailedException.builder(FailureKind.REJECTED,
                "550 player@example.com: mailbox unknown").transportName("primary").providerId("scripted")
                .providerCode("player@example.com").statusCode(550)
                .cause(new IllegalStateException("raw reply for player@example.com")).build();
        MailTransport throwsRaw = new MailTransport() {
            @Override
            public String name() {
                return "raw";
            }

            @Override
            public String providerId() {
                return "raw";
            }

            @Override
            public TransportCapabilities capabilities() {
                return ScriptedTransport.builder("x").build().capabilities();
            }

            @Override
            public SendResult send(Email email, SendContext context) {
                throw new IllegalArgumentException("player@example.com rejected");
            }
        };
        try (Mailroom mailroom = Mailroom.builder().retryBackoff(Duration.ZERO).failoverOnUnknownOutcome(true)
                .transport(ScriptedTransport.builder("primary").fail(failure).build())
                .transport(throwsRaw).build()) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(SendFailedException.class);
        }
        List<String> lines = LogLeakGuard.lines();
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("REJECTED via primary: 550 p***@example.com: mailbox unknown")
                .contains("(status 550)").contains("(code p***@example.com)"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("FINE attempt 1 on raw (raw) failed after ")
                .contains("UNKNOWN_OUTCOME via raw: IllegalArgumentException from transport"));
        assertThat(LogLeakGuard.records()).isNotEmpty().allSatisfy(record -> assertThat(record.getThrown()).isNull());
    }

    @Test
    void neverLogsAConfiguredSecret() {
        final List<MailTransport> created = new ArrayList<MailTransport>();
        TransportProvider keyed = new TransportProvider() {
            @Override
            public String id() {
                return "keyed";
            }

            @Override
            public Set<ConfigKey> configKeys() {
                return Collections.singleton(ConfigKey.of("api-key").required().secret());
            }

            @Override
            public MailTransport create(String name, TransportConfig config) {
                config.secret("api-key");
                MailTransport transport = ScriptedTransport.builder(name).providerId("keyed")
                        .fail(FailureKind.UNAVAILABLE).fail(FailureKind.REJECTED).build();
                created.add(transport);
                return transport;
            }
        };
        Map<String, String> props = new LinkedHashMap<String, String>();
        props.put("default-from", "shop@example.com");
        props.put("transports.primary.type", "keyed");
        props.put("transports.primary.api-key", LogLeakGuard.CANARY);
        try (Mailroom mailroom = Mailroom.fromProperties(props, TransportRegistry.of(keyed))) {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOf(SendFailedException.class);
        }
        assertThat(created).hasSize(1);
        assertThat(LogLeakGuard.lines()).isNotEmpty().noneMatch(line -> line.contains(LogLeakGuard.CANARY));

        props.put("transports.primary.api-kee", LogLeakGuard.CANARY);
        assertThatThrownBy(() -> Mailroom.fromProperties(props, TransportRegistry.of(keyed)))
                .isInstanceOf(InvalidConfigException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(LogLeakGuard.CANARY));
        assertThat(LogLeakGuard.lines()).noneMatch(line -> line.contains(LogLeakGuard.CANARY));
    }

    @Test
    void theGuardCatchesLeaks() {
        assertThat(LogLeakGuard.leaks("sent to player@example.com")).containsExactly("a full email address");
        assertThat(LogLeakGuard.leaks("api-key " + LogLeakGuard.CANARY)).containsExactly("the canary secret");
        assertThat(LogLeakGuard.leaks("skipped a: p***@example.com and ***@example.com")).isEmpty();
        assertThat(LogLeakGuard.leaks("sent via primary (sendgrid) after 1 attempt, messageId scripted-1")).isEmpty();
    }
}
