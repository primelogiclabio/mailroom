package io.primelogiclab.mailroom.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks that every canonical email ({@link TckEmails#all()}) reaches the provider intact: sender, recipients,
 * reply-to, subject, bodies, attachments, inline parts, custom headers, tags and metadata. Extend it in the transport's
 * tests and decode what the provider received back into an {@link Email}; that decoding is the transport's statement of
 * how it maps the model.
 *
 * <pre>{@code
 * class Smtp2GoMappingTest extends MappingContract {
 *     StubHttpServer server;
 *     @BeforeEach void start() { server = StubHttpServer.start(); }
 *     @AfterEach void stop() { server.close(); }
 *
 *     protected MailTransport newTransport() { return transportAt(server.baseUri()); }
 *     protected void acceptNextSend() { server.enqueue(StubResponse.status(200).json(OK_BODY)); }
 *     protected Email lastSent() { return decode(server.lastRequest().bodyAsString()); }
 * }
 * }</pre>
 *
 * <p>For each email the contract creates a transport, calls {@link #acceptNextSend()}, sends, and compares
 * {@link #lastSent()} with what it sent. Parts the transport's capabilities don't claim are not compared, and an email
 * that needs a capability the transport lacks is skipped. Recipients are compared as sets; line endings and trailing
 * newlines in bodies, and parameters of content types, don't count; extra headers are allowed. The send's result must
 * name the transport and provider, and carry a message id if the transport claims {@code MESSAGE_ID}.
 *
 * <p>All emails run in one {@code @TestFactory}, so a {@code @BeforeEach} runs once for all of them.
 *
 * @since 0.1.0
 */
public abstract class MappingContract {

    /**
     * For subclasses: the transport's contract test.
     *
     * @since 0.1.0
     */
    protected MappingContract() {
    }

    /**
     * Creates a transport to test, pointed at where {@link #lastSent()} can see what it sends.
     *
     * @return a new transport; the contract closes it
     * @since 0.1.0
     */
    protected abstract MailTransport newTransport();

    /**
     * Prepares the provider side to accept the next send, such as queueing a success response on a stub server.
     *
     * @since 0.1.0
     */
    protected abstract void acceptNextSend();

    /**
     * Decodes what the provider received last into an email: the request body, a captured MIME message.
     *
     * @return the received email
     * @since 0.1.0
     */
    protected abstract Email lastSent();

    /**
     * Returns the emails to check, by name; override to add provider-specific ones.
     *
     * @return {@link TckEmails#all()} by default
     * @since 0.1.0
     */
    protected Map<String, Email> emails() {
        return TckEmails.all();
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
     * Sends each email and compares what arrived.
     *
     * @return one test per email
     * @since 0.1.0
     */
    @TestFactory
    public List<DynamicTest> emailsArriveIntact() {
        final TransportCapabilities capabilities = Tck.probe(newTransport());
        List<DynamicTest> tests = new ArrayList<>();
        for (final Map.Entry<String, Email> entry : emails().entrySet()) {
            tests.add(DynamicTest.dynamicTest(entry.getKey(), new Executable() {
                @Override
                public void execute() {
                    check(entry.getKey(), entry.getValue(), capabilities);
                }
            }));
        }
        return tests;
    }

    private void check(String name, Email email, TransportCapabilities capabilities) {
        Assumptions.assumeTrue(TckEmails.carries(capabilities, email),
                "the transport doesn't claim " + Tck.missing(capabilities, email));
        MailTransport transport = newTransport();
        try {
            acceptNextSend();
            SendResult result = transport.send(email, sendContext());
            assertNotNull(result, name + ": send returned null");
            assertEquals(transport.name(), result.transportName(), name + ": the result's transportName()");
            assertEquals(transport.providerId(), result.providerId(), name + ": the result's providerId()");
            if (capabilities.supports(Capability.MESSAGE_ID)) {
                assertTrue(result.providerMessageId().isPresent(),
                        name + ": the transport claims MESSAGE_ID but the result has no providerMessageId()");
            }
            Email received = lastSent();
            assertNotNull(received, name + ": lastSent() returned null");
            List<String> differences = Tck.differences(email, received, capabilities);
            if (!differences.isEmpty()) {
                fail("\"" + name + "\" did not arrive intact:" + Tck.join(differences));
            }
        } finally {
            transport.close();
        }
    }
}
