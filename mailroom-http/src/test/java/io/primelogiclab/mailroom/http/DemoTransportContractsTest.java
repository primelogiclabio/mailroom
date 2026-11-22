package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.tck.FailureCase;
import io.primelogiclab.mailroom.tck.FailureContract;
import io.primelogiclab.mailroom.tck.HttpFailureCases;
import io.primelogiclab.mailroom.tck.RedactionContract;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

/** A real HTTP transport built on this module passes the TCK's failure and redaction contracts. */
class DemoTransportContractsTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private StubHttpServer server;

    @BeforeEach
    void start() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private List<FailureCase> cases() {
        List<FailureCase> cases = new ArrayList<FailureCase>(HttpFailureCases.standard(server));
        cases.add(HttpFailureCases.readTimeout(server, Duration.ofSeconds(1)));
        return cases;
    }

    private MailTransport transport(Secret apiKey) {
        return new DemoHttpTransport("demo-primary", server.uri("/send"), apiKey, READ_TIMEOUT);
    }

    @Nested
    class Failures extends FailureContract {
        @Override
        protected MailTransport newTransport() {
            return transport(Secret.of("not-the-canary"));
        }

        @Override
        protected List<FailureCase> failureCases() {
            return cases();
        }
    }

    @Nested
    class Redaction extends RedactionContract {
        @Override
        protected MailTransport newTransport(Secret secret) {
            return transport(secret);
        }

        @Override
        protected void acceptNextSend() {
            server.enqueue(StubResponse.status(200).json("{\"id\":\"m-1\"}"));
        }

        @Override
        protected List<FailureCase> failureCases() {
            return cases();
        }

        /** The JDK logs every request header, the API key included, at FINE; nothing in mailroom can stop it. */
        @Override
        protected Set<String> ignoredLoggers() {
            return Collections.singleton("sun.net.www.protocol.http.HttpURLConnection");
        }
    }
}
