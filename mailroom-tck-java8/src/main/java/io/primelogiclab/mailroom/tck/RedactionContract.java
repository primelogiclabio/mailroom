package io.primelogiclab.mailroom.tck;

import static org.junit.jupiter.api.Assertions.fail;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks that the transport never shows its credential or a full address where an application might log it: sends
 * {@link #CANARY_EMAIL} through a transport configured with {@link #CANARY_SECRET}, once succeeding and once per
 * {@link FailureCase}, while capturing every {@code java.util.logging} record at every level.
 *
 * <p>It fails if the secret, or one of the canary addresses in full, appears in: the exception's message or
 * {@code toString}, along its causes and suppressed exceptions; the {@link SendResult}'s or the transport's
 * {@code toString}; any log record's message, formatted message, parameters or {@code Throwable}. A masked address
 * such as {@code c***@tck-canary.example.com} is fine, and the secret may of course go to the provider.
 *
 * <p>Logging outside {@code java.util.logging} (SLF4J, standard out) isn't seen. A logger the transport can't
 * change, such as a library that logs request headers on its own, can be left out with {@link #ignoredLoggers()};
 * each one left out is a leak to document. (The JDK's {@code HttpURLConnection} logs headers at {@code FINE};
 * mailroom-http's engine keeps that logger at {@code INFO}, so it needs no entry.)
 *
 * @since 0.1.0
 */
public abstract class RedactionContract {

    /**
     * For subclasses: the transport's contract test.
     *
     * @since 0.1.0
     */
    protected RedactionContract() {
    }

    /**
     * The credential to configure the transport with. No message, log line or {@code toString} may show it.
     *
     * @since 0.1.0
     */
    public static final String CANARY_SECRET = "tck-canary-secret-5d41c7e9";

    /**
     * The email the contract sends. None of its addresses may appear in full.
     *
     * @since 0.1.0
     */
    public static final Email CANARY_EMAIL = Email.builder()
            .from(Address.of("Canary Sender", "canary.sender@tck-canary.example.com"))
            .to(Address.of("Canary To", "canary.to@tck-canary.example.com"))
            .cc("canary.cc@tck-canary.example.com").bcc("canary.bcc@tck-canary.example.com")
            .replyTo("canary.reply@tck-canary.example.com")
            .subject("TCK redaction").text("Nothing here may leak.").build();

    private static final List<String> CANARY_ADDRESSES = Collections.unmodifiableList(Arrays.asList(
            "canary.sender@tck-canary.example.com", "canary.to@tck-canary.example.com",
            "canary.cc@tck-canary.example.com", "canary.bcc@tck-canary.example.com",
            "canary.reply@tck-canary.example.com"));

    /**
     * Creates a transport configured with this credential, as its API key, password or client secret.
     *
     * @param secret the credential; {@link Secret#reveal()} gives {@link #CANARY_SECRET}
     * @return a new transport; the contract closes it
     * @since 0.1.0
     */
    protected abstract MailTransport newTransport(Secret secret);

    /**
     * Prepares the provider side to accept the next send.
     *
     * @since 0.1.0
     */
    protected abstract void acceptNextSend();

    /**
     * Returns the failures to send through; usually the same as the {@link FailureContract}'s.
     *
     * @return the cases; their own emails are replaced by {@link #CANARY_EMAIL}
     * @since 0.1.0
     */
    protected abstract List<FailureCase> failureCases();

    /**
     * Returns the loggers whose records aren't checked, for libraries the transport can't change. Every entry is a leak
     * at that logger's level: say so in the transport's documentation.
     *
     * @return logger names; each also covers the loggers below it; empty by default
     * @since 0.1.0
     */
    protected Set<String> ignoredLoggers() {
        return Collections.emptySet();
    }

    /**
     * Returns the context the sends get.
     *
     * @return a default context: attempt 1, the system clock
     * @since 0.1.0
     */
    protected SendContext sendContext() {
        return Tck.context();
    }

    /**
     * Sends successfully and checks the result, the transport and the logs.
     *
     * @since 0.1.0
     */
    @Test
    public void aSuccessfulSendLeaksNothing() {
        List<String> leaks = new ArrayList<String>();
        Tck.LogCapture logs = Tck.LogCapture.start();
        try {
            acceptNextSend();
            MailTransport transport = newTransport(Secret.of(CANARY_SECRET));
            try {
                SendResult result = transport.send(CANARY_EMAIL, sendContext());
                add(leaks, Tck.leak("the SendResult's toString()", String.valueOf(result), CANARY_SECRET,
                        CANARY_ADDRESSES));
                add(leaks, Tck.leak("the transport's toString()", transport.toString(), CANARY_SECRET,
                        CANARY_ADDRESSES));
            } finally {
                transport.close();
            }
        } finally {
            logs.stop();
        }
        leaks.addAll(Tck.leaks(logs, CANARY_SECRET, CANARY_ADDRESSES, ignoredLoggers()));
        report("a successful send", leaks);
    }

    /**
     * Sends through each failure case and checks the exception, the transport and the logs.
     *
     * @return one test per case
     * @since 0.1.0
     */
    @TestFactory
    public List<DynamicTest> failuresLeakNothing() {
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (final FailureCase failureCase : failureCases()) {
            tests.add(DynamicTest.dynamicTest(failureCase.name(), new Executable() {
                @Override
                public void execute() {
                    check(failureCase);
                }
            }));
        }
        return tests;
    }

    private void check(FailureCase failureCase) {
        List<String> leaks = new ArrayList<String>();
        Tck.LogCapture logs = Tck.LogCapture.start();
        try {
            failureCase.prime();
            MailTransport transport = newTransport(Secret.of(CANARY_SECRET));
            try {
                SendResult result = transport.send(CANARY_EMAIL, sendContext());
                add(leaks, Tck.leak("the SendResult's toString()", String.valueOf(result), CANARY_SECRET,
                        CANARY_ADDRESSES));
            } catch (RuntimeException e) {
                leaks.addAll(Tck.leaks(e, "the exception", CANARY_SECRET, CANARY_ADDRESSES));
            } finally {
                add(leaks, Tck.leak("the transport's toString()", transport.toString(), CANARY_SECRET,
                        CANARY_ADDRESSES));
                transport.close();
            }
        } finally {
            logs.stop();
        }
        leaks.addAll(Tck.leaks(logs, CANARY_SECRET, CANARY_ADDRESSES, ignoredLoggers()));
        report(failureCase.name(), leaks);
    }

    private static void add(List<String> leaks, String leak) {
        if (leak != null) {
            leaks.add(leak);
        }
    }

    private static void report(String what, List<String> leaks) {
        if (!leaks.isEmpty()) {
            fail(what + " leaked:" + Tck.join(leaks));
        }
    }
}
