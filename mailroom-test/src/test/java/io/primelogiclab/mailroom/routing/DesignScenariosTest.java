package io.primelogiclab.mailroom.routing;

import static io.primelogiclab.mailroom.routing.Routing.describe;
import static io.primelogiclab.mailroom.routing.Routing.email;
import static io.primelogiclab.mailroom.routing.Routing.failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.internal.Router;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The CORE-6 done-when: design scenarios 2, 4 and 5. */
class DesignScenariosTest {

    private final Routing.RecordingSleeper sleeper = new Routing.RecordingSleeper();

    /** Scenario 2: the primary is down, one retry, then the backup takes the email. */
    @Test
    void scenario2PrimaryOutageFailsOverToTheBackup() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").providerId("sendgrid")
                .fail(FailureKind.UNAVAILABLE, 503).fail(FailureKind.UNAVAILABLE, 503).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").providerId("smtp2go").build();
        Router router = Router.builder().transport(primary).transport(backup).defaultRoute("primary", "backup")
                .sleeper(sleeper).build();

        SendResult result = router.send(email());

        assertThat(result.transportName()).isEqualTo("backup");
        assertThat(result.providerId()).isEqualTo("smtp2go");
        assertThat(describe(result.attempts()))
                .containsExactly("primary UNAVAILABLE", "primary UNAVAILABLE", "backup SUCCEEDED");
        assertThat(result.attempts().get(0).statusCode()).hasValue(503);
        assertThat(primary.contexts()).extracting(SendContext::attempt).containsExactly(1, 2);
        assertThat(sleeper.waits).hasSize(1);
        assertThat(sleeper.waits.get(0)).isBetween(Duration.ofMillis(125), Duration.ofMillis(375));
        backup.assertCalls(1);
    }

    /** Scenario 4: a read timeout after the request was sent is not failed over by default. */
    @Test
    void scenario4UnknownOutcomeIsNotFailedOver() {
        ScriptedTransport internal = ScriptedTransport.builder("internal").providerId("m365")
                .fail(FailureKind.UNKNOWN_OUTCOME).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        Router router = Router.builder().transport(internal).transport(backup).sleeper(sleeper).build();

        SendFailedException e = failure(() -> router.send(email()));

        assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(e.transportName()).contains("internal");
        assertThat(describe(e.attempts())).containsExactly("internal UNKNOWN_OUTCOME");
        backup.assertCalls(0);
        assertThat(sleeper.waits).isEmpty();
    }

    @Test
    void scenario4CanFailOverWhenTheCallerAcceptsTheDuplicateRisk() {
        ScriptedTransport internal = ScriptedTransport.builder("internal").fail(FailureKind.UNKNOWN_OUTCOME).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        Router router = Router.builder().transport(internal).transport(backup).failoverOnUnknownOutcome(true)
                .sleeper(sleeper).build();

        assertThat(describe(router.send(email()).attempts()))
                .containsExactly("internal UNKNOWN_OUTCOME", "backup SUCCEEDED");
    }

    /** Scenario 5: an essential template never goes to a provider that doesn't understand it. */
    @Test
    void scenario5TheTemplateIsNeverSentThroughTheBackup() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").providerId("sendgrid")
                .understands(Routing.SendGridOptions.class)
                .fail(FailureKind.UNAVAILABLE).fail(FailureKind.UNAVAILABLE).build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").providerId("smtp2go").build();
        Router router = Router.builder().transport(primary).transport(backup).sleeper(sleeper).build();
        Email welcome = Email.builder().to("player@example.com").with(new Routing.SendGridOptions()).build();

        SendFailedException e = failure(() -> router.send(welcome));

        assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(describe(e.attempts())).containsExactly("primary UNAVAILABLE", "primary UNAVAILABLE");
        backup.assertCalls(0);
    }

    @Test
    void scenario5WithNoTransportAbleToCarryTheTemplate() {
        ScriptedTransport primary = ScriptedTransport.builder("primary").understands(Routing.SendGridOptions.class)
                .rejectPrecheck("circuit open").build();
        ScriptedTransport backup = ScriptedTransport.builder("backup").build();
        Router router = Router.builder().transport(primary).transport(backup).build();
        Email welcome = Email.builder().to("player@example.com").with(new Routing.SendGridOptions()).build();

        assertThatThrownBy(() -> router.send(welcome))
                .isInstanceOfSatisfying(NoEligibleTransportException.class, e -> {
                    assertThat(e.skipReasons()).containsEntry("backup", "doesn't understand SendGridOptions (essential)");
                    assertThat(e.getMessage()).isEqualTo("No transport can carry this email: primary: circuit open; "
                            + "backup: doesn't understand SendGridOptions (essential)");
                });
        assertThatThrownBy(() -> router.send(welcome, java.util.Collections.singletonList("backup")))
                .isInstanceOf(NoEligibleTransportException.class)
                .hasMessage("No transport can carry this email: backup: doesn't understand SendGridOptions (essential)");
        primary.assertCalls(0);
        backup.assertCalls(0);
    }
}
