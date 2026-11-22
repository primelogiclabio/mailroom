package io.primelogiclab.mailroom.tck;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/** Each contract fails a transport that breaks it, with a message that says what broke. */
class ContractsCatchMistakesTest {

    // ---------------------------------------------------------------------------------------------------------------
    // Mapping

    @Test
    void mappingCatchesAChangedSubjectAndDroppedTags() {
        Map<String, Throwable> changed = run(new LossyMapping(false).emailsArriveIntact());
        assertThat(changed).hasSize(TckEmails.all().size());
        assertThat(changed.get("minimal text")).hasMessageContaining("\"minimal text\" did not arrive intact")
                .hasMessageContaining("subject: expected \"TCK minimal\" but was \"changed\"");

        Map<String, Throwable> tagless = run(new LossyMapping(true).emailsArriveIntact());
        assertThat(tagless.get("minimal text")).isNull();
        assertThat(tagless.get("tags")).hasMessageContaining("tags: expected [tck, welcome] but was []");
        assertThat(tagless.get("everything")).hasMessageContaining("tags: expected [everything, tck] but was []");
    }

    @Test
    void mappingSkipsEmailsTheTransportDoesNotClaim() {
        Map<String, Throwable> results = run(new MappingContract() {
            private InMemoryTransport latest;

            @Override
            protected MailTransport newTransport() {
                latest = InMemoryTransport.builder("text-only")
                        .capabilities(TransportCapabilities.builder()
                                .supports(io.primelogiclab.mailroom.spi.Capability.TEXT).build())
                        .build();
                return latest;
            }

            @Override
            protected void acceptNextSend() {
            }

            @Override
            protected Email lastSent() {
                return latest.lastSent().get();
            }
        }.emailsArriveIntact());

        assertThat(results.get("minimal text")).isNull();
        assertThat(results.get("tags")).isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("the transport doesn't claim [tags]");
        assertThat(results.get("everything")).isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("[attachments, custom-headers, html, inline-images, metadata, tags]");
    }

    /** Keeps what InMemoryTransport received, but changes the subject or drops the tags on the way back. */
    private static final class LossyMapping extends MappingContract {
        private final boolean dropTags;
        private InMemoryTransport latest;

        LossyMapping(boolean dropTags) {
            this.dropTags = dropTags;
        }

        @Override
        protected MailTransport newTransport() {
            latest = InMemoryTransport.named("lossy");
            return latest;
        }

        @Override
        protected void acceptNextSend() {
        }

        @Override
        protected Email lastSent() {
            Email sent = latest.lastSent().get();
            return dropTags ? withoutTags(sent) : sent.toBuilder().subject("changed").build();
        }
    }

    private static Email withoutTags(Email email) {
        Email.Builder b = Email.builder().from(email.from().get()).subject(email.subject().get());
        b.to(email.to()).cc(email.cc()).bcc(email.bcc());
        for (io.primelogiclab.mailroom.Address replyTo : email.replyTo()) {
            b.replyTo(replyTo);
        }
        if (email.text().isPresent()) {
            b.text(email.text().get());
        }
        if (email.html().isPresent()) {
            b.html(email.html().get());
        }
        for (Attachment attachment : email.attachments()) {
            b.attach(attachment);
        }
        for (Map.Entry<String, String> header : email.headers().entrySet()) {
            b.header(header.getKey(), header.getValue());
        }
        for (Map.Entry<String, String> entry : email.metadata().entrySet()) {
            b.metadata(entry.getKey(), entry.getValue());
        }
        return b.build();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Failures

    @Test
    void failuresCatchTheWrongKindAMissingNameAndASuccess() {
        Map<String, Throwable> results = run(new FailureContract() {
            private MailTransport next;

            @Override
            protected MailTransport newTransport() {
                return next;
            }

            @Override
            protected List<FailureCase> failureCases() {
                return java.util.Arrays.asList(
                        FailureCase.of("wrong kind", () -> next = ScriptedTransport.builder("t")
                                .fail(FailureKind.UNAVAILABLE).build(), FailureKind.AUTHENTICATION),
                        FailureCase.of("no transport name", () -> next = ScriptedTransport.builder("t")
                                .fail(SendFailedException.builder(FailureKind.UNAVAILABLE, "boom").build()).build(),
                                FailureKind.UNAVAILABLE),
                        FailureCase.of("succeeds", () -> next = ScriptedTransport.builder("t").build(),
                                FailureKind.UNAVAILABLE),
                        FailureCase.of("wrong status", () -> next = ScriptedTransport.builder("t")
                                .fail(FailureKind.UNAVAILABLE, 502).build(), FailureKind.UNAVAILABLE).status(503),
                        FailureCase.of("throws something else", () -> next = new Throwing(), FailureKind.UNAVAILABLE));
            }
        }.failuresAreClassified());

        assertThat(results.get("wrong kind")).hasMessageContaining("wrong kind: kind")
                .hasMessageContaining("AUTHENTICATION").hasMessageContaining("UNAVAILABLE");
        assertThat(results.get("no transport name")).hasMessageContaining("transportName()")
                .hasMessageContaining("toBuilder().transportName(name())");
        assertThat(results.get("succeeds")).hasMessageContaining("succeeds: the send succeeded");
        assertThat(results.get("wrong status")).hasMessageContaining("wrong status: statusCode()");
        assertThat(results.get("throws something else"))
                .hasMessageContaining("threw java.lang.IllegalStateException instead of SendFailedException");
    }

    @Test
    void failuresNeedAtLeastOneCase() {
        Map<String, Throwable> results = run(new FailureContract() {
            @Override
            protected MailTransport newTransport() {
                return ScriptedTransport.builder("t").build();
            }

            @Override
            protected List<FailureCase> failureCases() {
                return Collections.emptyList();
            }
        }.failuresAreClassified());
        assertThat(results.get("failure cases")).hasMessage("failureCases() returned no cases");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Redaction

    @Test
    void redactionCatchesTheSecretAndFullAddressesInCausesLogsAndToString() {
        Map<String, Throwable> results = run(new LeakyRedaction().failuresLeakNothing());

        assertThat(results.get("secret in the cause"))
                .hasMessageContaining("the exception's cause (java.lang.IllegalStateException) getMessage() shows the "
                        + "secret");
        assertThat(results.get("address in the cause")).hasMessageContaining(
                "shows the full address canary.to@tck-canary.example.com; mask it (Address.toString() does)");
        assertThat(results.get("secret in a log record")).hasMessageContaining("a SEVERE log record from tck.leaky")
                .hasMessageContaining("shows the secret");
        assertThat(results.get("clean failure")).isNull();

        Throwable success = null;
        try {
            new LeakyRedaction().aSuccessfulSendLeaksNothing();
        } catch (Throwable e) {
            success = e;
        }
        assertThat(success).hasMessageContaining("the transport's toString() shows the secret");
    }

    @Test
    void redactionLeavesOutOnlyTheLoggersNamed() {
        Map<String, Throwable> parentNamed = run(new LeakyRedaction() {
            @Override
            protected java.util.Set<String> ignoredLoggers() {
                return Collections.singleton("tck");
            }
        }.failuresLeakNothing());
        assertThat(parentNamed.get("secret in a log record")).isNull();
        assertThat(parentNamed.get("secret in the cause")).isNotNull();

        Map<String, Throwable> prefixOnly = run(new LeakyRedaction() {
            @Override
            protected java.util.Set<String> ignoredLoggers() {
                return Collections.singleton("tck.lea");
            }
        }.failuresLeakNothing());
        assertThat(prefixOnly.get("secret in a log record")).hasMessageContaining("tck.leaky");
    }

    /** A transport that shows the secret in its toString and leaks in the ways its failure cases ask for. */
    private static class LeakyRedaction extends RedactionContract {
        private String mode = "clean";

        @Override
        protected MailTransport newTransport(Secret secret) {
            return new Leaky(secret, mode);
        }

        @Override
        protected void acceptNextSend() {
            mode = "clean";
        }

        @Override
        protected List<FailureCase> failureCases() {
            return java.util.Arrays.asList(
                    FailureCase.of("secret in the cause", () -> mode = "cause-secret", FailureKind.AUTHENTICATION),
                    FailureCase.of("address in the cause", () -> mode = "cause-address", FailureKind.REJECTED),
                    FailureCase.of("secret in a log record", () -> mode = "log-secret", FailureKind.UNAVAILABLE),
                    FailureCase.of("clean failure", () -> mode = "clean-failure", FailureKind.UNAVAILABLE));
        }
    }

    private static final class Leaky implements MailTransport {
        private static final Logger LOG = Logger.getLogger("tck.leaky");
        private final Secret secret;
        private final String mode;

        Leaky(Secret secret, String mode) {
            this.secret = secret;
            this.mode = mode;
        }

        @Override
        public String name() {
            return "leaky";
        }

        @Override
        public String providerId() {
            return "leaky";
        }

        @Override
        public TransportCapabilities capabilities() {
            return TransportCapabilities.builder().build();
        }

        @Override
        public SendResult send(Email email, SendContext context) {
            SendFailedException.Builder failure = SendFailedException.builder(FailureKind.UNAVAILABLE, "failed")
                    .transportName(name()).providerId(providerId());
            if (mode.equals("cause-secret")) {
                throw failure.cause(new IllegalStateException("key " + secret.reveal() + " refused")).build();
            }
            if (mode.equals("cause-address")) {
                throw failure.cause(new IllegalStateException("550 " + email.to().get(0).address() + " unknown"))
                        .build();
            }
            if (mode.equals("log-secret")) {
                LOG.severe("using key " + secret.reveal());
                throw failure.build();
            }
            if (mode.equals("clean-failure")) {
                throw failure.build();
            }
            return SendResult.builder().transportName(name()).providerId(providerId()).providerMessageId("m-1")
                    .acceptedAt(context.clock().instant()).build();
        }

        @Override
        public String toString() {
            return mode.equals("clean") ? "Leaky[key=" + secret.reveal() + "]" : "Leaky";
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Precheck

    @Test
    void precheckCatchesRejectingCanonicalEmailsPassingRejectedOnesAndThrowing() {
        Map<String, Throwable> rejectsAll = run(new PrecheckContract() {
            @Override
            protected MailTransport newTransport() {
                return ScriptedTransport.builder("t").rejectPrecheck("too large").build();
            }

            @Override
            protected Map<String, Email> rejectedEmails() {
                return Collections.emptyMap();
            }
        }.carryableEmailsPass());
        assertThat(rejectsAll.get("minimal text"))
                .hasMessageContaining("\"minimal text\": precheck rejected a canonical email: too large");

        Map<String, Email> rejected = new LinkedHashMap<String, Email>();
        rejected.put("should be too big", TckEmails.minimal());
        Map<String, Throwable> passes = run(new PrecheckContract() {
            @Override
            protected MailTransport newTransport() {
                return ScriptedTransport.builder("t").build();
            }

            @Override
            protected Map<String, Email> rejectedEmails() {
                return rejected;
            }
        }.rejectedEmailsFail());
        assertThat(passes.get("should be too big")).hasMessageContaining("\"should be too big\": precheck passed it");

        Map<String, Throwable> throwing = run(new PrecheckContract() {
            @Override
            protected MailTransport newTransport() {
                return new Throwing();
            }

            @Override
            protected Map<String, Email> rejectedEmails() {
                return Collections.emptyMap();
            }
        }.carryableEmailsPass());
        assertThat(throwing.get("minimal text")).hasMessageContaining(
                "precheck threw java.lang.IllegalStateException; it must return Precheck.reject(reason) instead");
    }

    /** Throws from both send and precheck, as a buggy transport would. */
    private static final class Throwing implements MailTransport {
        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public String providerId() {
            return "throwing";
        }

        @Override
        public TransportCapabilities capabilities() {
            return TransportCapabilities.builder().supports(io.primelogiclab.mailroom.spi.Capability.TEXT).build();
        }

        @Override
        public Precheck precheck(Email email) {
            throw new IllegalStateException("bug");
        }

        @Override
        public SendResult send(Email email, SendContext context) {
            throw new IllegalStateException("bug");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Runs dynamic tests the way JUnit would, keeping each one's failure (or null when it passed). */
    private static Map<String, Throwable> run(List<DynamicTest> tests) {
        Map<String, Throwable> results = new LinkedHashMap<String, Throwable>();
        for (DynamicTest test : tests) {
            try {
                test.getExecutable().execute();
                results.put(test.getDisplayName(), null);
            } catch (Throwable e) {
                results.put(test.getDisplayName(), e);
            }
        }
        return results;
    }
}
