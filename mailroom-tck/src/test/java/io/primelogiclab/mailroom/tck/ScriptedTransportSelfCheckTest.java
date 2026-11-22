package io.primelogiclab.mailroom.tck;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;

/**
 * The done check of TCK-2: the test kit's own transports pass the contracts. {@code ScriptedTransport} passes the
 * failure, redaction and precheck contracts; {@code InMemoryTransport}, which keeps the email it was given, passes the
 * mapping contract, which proves the comparison itself.
 */
class ScriptedTransportSelfCheckTest {

    @Nested
    class Failures extends FailureContract {
        private final ScriptedCases scripted = new ScriptedCases();

        @Override
        protected MailTransport newTransport() {
            return scripted.take();
        }

        @Override
        protected List<FailureCase> failureCases() {
            return scripted.all();
        }
    }

    @Nested
    class Redaction extends RedactionContract {
        private final ScriptedCases scripted = new ScriptedCases();

        @Override
        protected MailTransport newTransport(Secret secret) {
            return scripted.take();
        }

        @Override
        protected void acceptNextSend() {
            // an unscripted call succeeds
        }

        @Override
        protected List<FailureCase> failureCases() {
            return scripted.all();
        }
    }

    @Nested
    class Prechecks extends PrecheckContract {
        @Override
        protected MailTransport newTransport() {
            return ScriptedTransport.builder("scripted-tck").build();
        }

        @Override
        protected Map<String, Email> rejectedEmails() {
            return Collections.emptyMap();
        }
    }

    @Nested
    class Mapping extends MappingContract {
        private InMemoryTransport latest;

        @Override
        protected MailTransport newTransport() {
            latest = InMemoryTransport.named("memory");
            return latest;
        }

        @Override
        protected void acceptNextSend() {
            // always accepts
        }

        @Override
        protected Email lastSent() {
            return latest.lastSent().get();
        }
    }
}
