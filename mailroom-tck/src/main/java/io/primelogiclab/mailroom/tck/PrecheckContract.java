package io.primelogiclab.mailroom.tck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks the transport's {@code precheck}: it passes every canonical email the transport claims to carry, rejects
 * each of {@link #rejectedEmails()} with a reason that shows no full address, and never throws.
 *
 * <pre>{@code
 * class Smtp2GoPrecheckTest extends PrecheckContract {
 *     protected MailTransport newTransport() { return transportAt(URI.create("https://unused.example.com")); }
 *     protected Map<String, Email> rejectedEmails() {
 *         Map<String, Email> rejected = new LinkedHashMap<String, Email>();
 *         rejected.put("over 50 MB", emailWithAttachmentOf(51 * 1024 * 1024));
 *         rejected.put("101 recipients", emailTo(101));
 *         return rejected;
 *     }
 * }
 * }</pre>
 *
 * <p>Precheck runs before anything is sent, so no provider side is needed.
 *
 * @since 0.1.0
 */
public abstract class PrecheckContract {

    /**
     * For subclasses: the transport's contract test.
     *
     * @since 0.1.0
     */
    protected PrecheckContract() {
    }

    /**
     * Creates a transport to test.
     *
     * @return a new transport; the contract closes it
     * @since 0.1.0
     */
    protected abstract MailTransport newTransport();

    /**
     * Returns emails the provider can't take, by what is wrong with them, such as {@code "over 50 MB"}. May be
     * empty when the router's capability and limit checks already cover everything.
     *
     * @return the emails precheck must reject
     * @since 0.1.0
     */
    protected abstract Map<String, Email> rejectedEmails();

    /**
     * Checks that each canonical email the transport can carry passes.
     *
     * @return one test per canonical email
     * @since 0.1.0
     */
    @TestFactory
    public List<DynamicTest> carryableEmailsPass() {
        final TransportCapabilities capabilities = Tck.probe(newTransport());
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (final Map.Entry<String, Email> entry : TckEmails.all().entrySet()) {
            tests.add(DynamicTest.dynamicTest(entry.getKey(), new Executable() {
                @Override
                public void execute() {
                    Assumptions.assumeTrue(TckEmails.carries(capabilities, entry.getValue()),
                            "the transport doesn't claim " + Tck.missing(capabilities, entry.getValue()));
                    Precheck precheck = precheck(entry.getKey(), entry.getValue());
                    assertTrue(precheck.passed(), "\"" + entry.getKey() + "\": precheck rejected a canonical email: "
                            + precheck.reason().orElse(""));
                }
            }));
        }
        return tests;
    }

    /**
     * Checks that each of {@link #rejectedEmails()} is rejected with a reason.
     *
     * @return one test per rejected email
     * @since 0.1.0
     */
    @TestFactory
    public List<DynamicTest> rejectedEmailsFail() {
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (final Map.Entry<String, Email> entry : rejectedEmails().entrySet()) {
            tests.add(DynamicTest.dynamicTest(entry.getKey(), new Executable() {
                @Override
                public void execute() {
                    Precheck precheck = precheck(entry.getKey(), entry.getValue());
                    assertFalse(precheck.passed(), "\"" + entry.getKey() + "\": precheck passed it");
                    assertTrue(precheck.reason().isPresent(),
                            "\"" + entry.getKey() + "\": the rejection has no reason");
                    String leak = Tck.leak("\"" + entry.getKey() + "\": the reason", precheck.reason().get(), null,
                            addresses(entry.getValue()));
                    if (leak != null) {
                        fail(leak);
                    }
                }
            }));
        }
        return tests;
    }

    private Precheck precheck(String name, Email email) {
        MailTransport transport = newTransport();
        try {
            Precheck precheck = transport.precheck(email);
            assertNotNull(precheck, "\"" + name + "\": precheck returned null");
            return precheck;
        } catch (RuntimeException e) {
            throw Assertions.<AssertionError>fail("\"" + name + "\": precheck threw "
                    + e.getClass().getName() + "; it must return Precheck.reject(reason) instead", e);
        } finally {
            transport.close();
        }
    }

    private static List<String> addresses(Email email) {
        List<String> addresses = new ArrayList<String>();
        if (email.from().isPresent()) {
            addresses.add(email.from().get().address());
        }
        for (Address address : email.recipients()) {
            addresses.add(address.address());
        }
        for (Address address : email.replyTo()) {
            addresses.add(address.address());
        }
        return addresses;
    }
}
