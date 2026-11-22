package io.primelogiclab.mailroom.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks that the transport classifies its failures: for each {@link FailureCase}, the send throws a
 * {@link SendFailedException} of the expected {@code FailureKind}, with the expected status and retry-after where the
 * case states them, naming this transport and its provider.
 *
 * <pre>{@code
 * class Smtp2GoFailureTest extends FailureContract {
 *     StubHttpServer server;
 *     @BeforeEach void start() { server = StubHttpServer.start(); }
 *     @AfterEach void stop() { server.close(); }
 *
 *     protected MailTransport newTransport() { return transportAt(server.baseUri(), Duration.ofMillis(300)); }
 *     protected List<FailureCase> failureCases() {
 *         List<FailureCase> cases = new ArrayList<>(HttpFailureCases.standard(server));
 *         cases.add(HttpFailureCases.readTimeout(server, Duration.ofSeconds(2)));
 *         cases.add(FailureCase.of("endpoint not enabled", ..., FailureKind.AUTHENTICATION).status(400));
 *         return cases;
 *     }
 * }
 * }</pre>
 *
 * <p>For each case the contract calls {@link FailureCase#prime()}, then {@link #newTransport()}, then sends the case's
 * email. Any other exception, or a successful send, fails the case. All cases run in one {@code @TestFactory}, so a
 * {@code @BeforeEach} runs once for all of them.
 *
 * @since 0.1.0
 */
public abstract class FailureContract {

    /**
     * For subclasses: the transport's contract test.
     *
     * @since 0.1.0
     */
    protected FailureContract() {
    }

    /**
     * Creates a transport to test, after the case was primed.
     *
     * @return a new transport; the contract closes it
     * @since 0.1.0
     */
    protected abstract MailTransport newTransport();

    /**
     * Returns the failures to check; at least one. {@link HttpFailureCases} has the standard ones for HTTP providers.
     *
     * @return the cases
     * @since 0.1.0
     */
    protected abstract List<FailureCase> failureCases();

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
     * Runs each failure case.
     *
     * @return one test per case
     * @since 0.1.0
     */
    @TestFactory
    public List<DynamicTest> failuresAreClassified() {
        List<FailureCase> cases = failureCases();
        List<DynamicTest> tests = new ArrayList<>();
        if (cases == null || cases.isEmpty()) {
            tests.add(DynamicTest.dynamicTest("failure cases", new Executable() {
                @Override
                public void execute() {
                    fail("failureCases() returned no cases");
                }
            }));
            return tests;
        }
        for (final FailureCase failureCase : cases) {
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
        String name = failureCase.name();
        failureCase.prime();
        MailTransport transport = newTransport();
        SendFailedException failure;
        try {
            transport.send(failureCase.email(), sendContext());
            throw Assertions.<AssertionError>fail(name + ": the send succeeded; expected a " + failureCase.kind()
                    + " failure");
        } catch (SendFailedException e) {
            failure = e;
        } catch (RuntimeException e) {
            throw Assertions.<AssertionError>fail(name + ": threw " + e.getClass().getName()
                    + " instead of SendFailedException", e);
        } finally {
            transport.close();
        }
        assertEquals(failureCase.kind(), failure.kind(), name + ": kind");
        if (failureCase.status().isPresent()) {
            assertEquals(failureCase.status(), failure.statusCode(), name + ": statusCode()");
        }
        if (failureCase.retryAfter().isPresent()) {
            assertEquals(failureCase.retryAfter(), failure.retryAfter(), name + ": retryAfter()");
        }
        assertEquals(Optional.of(transport.name()), failure.transportName(),
                name + ": transportName(); a transport adds it with toBuilder().transportName(name())");
        assertEquals(Optional.of(transport.providerId()), failure.providerId(), name + ": providerId()");
    }
}
