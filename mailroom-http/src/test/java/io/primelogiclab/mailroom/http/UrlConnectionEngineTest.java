package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.tck.RecordedRequest;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP-1 done-when: the HttpURLConnection engine against the TCK stub server, on JDK 8, 21 and 25. */
class UrlConnectionEngineTest {

    /**
     * Windows retries a refused loopback connect for about two seconds before reporting it, so the connect timeout is
     * generous unless a test is about the short one.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpEngine engine = HttpEngines.urlConnection();
    private StubHttpServer server;

    @BeforeEach
    void start() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpRequest.Builder post(String pathAndQuery) {
        return HttpRequest.post(server.uri(pathAndQuery)).connectTimeout(CONNECT_TIMEOUT);
    }

    private SendFailedException failing(HttpRequest request) {
        try {
            HttpResponse response = engine.execute(request);
            throw new AssertionError("expected SendFailedException, got " + response);
        } catch (SendFailedException e) {
            return e;
        }
    }

    @Test
    void isTheUrlConnectionEngine() {
        assertThat(engine.name()).isEqualTo("urlconnection");
        engine.close();     // holds nothing; closing is harmless
    }

    @Test
    void postsJsonAndReadsTheWholeResponse() {
        server.enqueue(StubResponse.status(200).json("{\"data\":{\"email_id\":\"abc\"}}").header("X-Request-Id", "r1"));

        HttpResponse response = engine.execute(post("/v3/email/send?region=eu")
                .header("X-Api-Key", "k-123")
                .header("Accept", "application/json")
                .json("{\"sender\":\"é\"}")
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("content-type")).contains("application/json; charset=utf-8");
        assertThat(response.header("X-Request-Id")).contains("r1");
        assertThat(response.bodyAsString()).isEqualTo("{\"data\":{\"email_id\":\"abc\"}}");

        RecordedRequest recorded = server.lastRequest();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/v3/email/send");
        assertThat(recorded.query()).contains("region=eu");
        assertThat(recorded.header("X-Api-Key")).contains("k-123");
        assertThat(recorded.header("Accept")).contains("application/json");
        assertThat(recorded.header("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(recorded.header("Content-Length")).contains("15");
        assertThat(recorded.header("Transfer-Encoding")).isEmpty();
        assertThat(recorded.bodyAsString()).isEqualTo("{\"sender\":\"é\"}");
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void sendsPutGetAndDeleteAsThemselves() {
        engine.execute(HttpRequest.put(server.uri("/upload")).body("application/octet-stream", new byte[] {1, 2, 3})
                .build());
        engine.execute(HttpRequest.get(server.uri("/status")).build());
        engine.execute(HttpRequest.delete(server.uri("/draft/1")).build());

        List<RecordedRequest> requests = server.requests();
        assertThat(requests).extracting(RecordedRequest::method).containsExactly("PUT", "GET", "DELETE");
        assertThat(requests.get(0).body()).containsExactly(1, 2, 3);
        assertThat(requests.get(0).header("Content-Type")).contains("application/octet-stream");
        assertThat(requests.get(1).body()).isEmpty();      // HttpURLConnection didn't turn the GET into a POST
        assertThat(requests.get(2).path()).isEqualTo("/draft/1");
    }

    @Test
    void anEmptyPostStillSendsContentLength() {
        engine.execute(post("/ping").build());
        assertThat(server.lastRequest().header("Content-Length")).contains("0");
    }

    @Test
    void errorStatusesComeBackAsResponses() {
        server.enqueue(StubResponse.status(400).json("{\"error\":\"bad sender\"}"),
                StubResponse.status(503).body("try later", "text/plain"),
                StubResponse.status(500),
                StubResponse.status(204),
                StubResponse.status(404).json("{}"));

        HttpResponse bad = engine.execute(post("/a").build());
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.isSuccessful()).isFalse();
        assertThat(bad.bodyAsString()).isEqualTo("{\"error\":\"bad sender\"}");
        HttpResponse down = engine.execute(post("/b").build());
        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(down.bodyAsString()).isEqualTo("try later");
        HttpResponse empty = engine.execute(post("/c").build());
        assertThat(empty.statusCode()).isEqualTo(500);
        assertThat(empty.body()).isEmpty();
        HttpResponse noContent = engine.execute(post("/d").build());
        assertThat(noContent.statusCode()).isEqualTo(204);
        assertThat(noContent.body()).isEmpty();
        assertThat(engine.execute(post("/e").build()).statusCode()).isEqualTo(404);
        assertThat(server.requestCount()).isEqualTo(5);
    }

    @Test
    void repeatedResponseHeadersKeepTheirOrder() {
        server.enqueue(StubResponse.status(200).header("Link", "<a>").header("Link", "<b>").header("Link", "<c>"));
        assertThat(engine.execute(post("/x").build()).headers("link")).containsExactly("<a>", "<b>", "<c>");
    }

    @Test
    void doesNotFollowRedirects() {
        server.enqueue(StubResponse.status(302).header("Location", server.uri("/elsewhere").toString()));

        HttpResponse response = engine.execute(post("/x").json("{}").build());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.header("Location")).contains(server.uri("/elsewhere").toString());
        assertThat(server.requestCount()).isEqualTo(1);
    }

    /** In streaming mode HttpURLConnection drops a 401 body unread; the status still comes back. */
    @Test
    void unauthorizedComesBackAsAResponseWithoutItsBody() {
        server.enqueue(StubResponse.status(401).header("WWW-Authenticate", "Bearer").json("{\"error\":\"bad key\"}"),
                StubResponse.status(401).header("WWW-Authenticate", "Basic realm=\"api\"").json("{}"),
                StubResponse.status(403).json("{\"error\":\"forbidden\"}"));

        HttpResponse bearer = engine.execute(post("/x").json("{}").build());
        assertThat(bearer.statusCode()).isEqualTo(401);
        assertThat(bearer.body()).isEmpty();
        assertThat(engine.execute(post("/y").json("{}").build()).statusCode()).isEqualTo(401);
        HttpResponse forbidden = engine.execute(post("/z").json("{}").build());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.bodyAsString()).isEqualTo("{\"error\":\"forbidden\"}");
        assertThat(server.requestCount()).isEqualTo(3);
    }

    @Test
    void aRefusedConnectionIsUnavailable() {
        URI refused = StubHttpServer.refusedUri();
        HttpRequest request = HttpRequest.post(URI.create(refused + "/v3/email/send")).connectTimeout(CONNECT_TIMEOUT)
                .json("{}").build();

        SendFailedException e = failing(request);

        assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(e.getMessage()).startsWith("UNAVAILABLE: POST " + refused + "/v3/email/send failed before the "
                + "request was sent: ");
        assertThat(e.getCause()).isInstanceOf(IOException.class);
        assertThat(e.transportName()).isEmpty();
        assertThat(e.providerId()).isEmpty();
    }

    /** On Windows, JDK 8 reports a refused loopback connect under a short timeout as "connect timed out". */
    @Test
    void aConnectTimeoutIsUnavailable() {
        HttpRequest request = HttpRequest.post(URI.create(StubHttpServer.refusedUri() + "/x"))
                .connectTimeout(Duration.ofMillis(500)).build();

        assertThat(failing(request).kind()).isEqualTo(FailureKind.UNAVAILABLE);
    }

    @Test
    void aTlsHandshakeFailureIsUnavailable() {
        URI plain = server.baseUri();
        HttpRequest request = HttpRequest.post(URI.create("https://" + plain.getHost() + ":" + plain.getPort() + "/x"))
                .connectTimeout(CONNECT_TIMEOUT).readTimeout(Duration.ofSeconds(2)).json("{}").build();

        SendFailedException e = failing(request);

        assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void aDroppedConnectionIsUnknownOutcomeAndNotResent() {
        server.enqueue(StubResponse.drop());       // a retry would get the fallback 200 and succeed

        SendFailedException e = failing(post("/v3/email/send").json("{}").build());

        assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(e.getMessage()).startsWith("UNKNOWN_OUTCOME: POST " + server.baseUri() + "/v3/email/send failed "
                + "after the request was sent; the provider may have accepted it: ");
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void aReadTimeoutIsUnknownOutcomeAndNotResent() {
        server.enqueue(StubResponse.status(200).delay(Duration.ofSeconds(3)));

        SendFailedException e = failing(post("/x").readTimeout(Duration.ofMillis(300)).json("{}").build());

        assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(e.getCause()).isInstanceOf(SocketTimeoutException.class);
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void aResponseCutShortIsUnknownOutcome() {
        server.enqueue(StubResponse.status(200).json("{\"data\":{\"email_id\":\"abcdefghij\"}}").dropAfter(10),
                StubResponse.status(503).json("{\"error\":\"overloaded, please retry\"}").dropAfter(5));

        assertThat(failing(post("/x").json("{}").build()).kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(failing(post("/y").json("{}").build()).kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(server.requestCount()).isEqualTo(2);
    }

    @Test
    void failuresNeverShowTheQueryHeaderValuesBodyOrAddresses() {
        String canary = "canary-7f3a";
        server.enqueue(StubResponse.drop());
        HttpRequest dropped = post("/users/sender@example.com/send?key=" + canary)
                .header("Authorization", "Bearer " + canary)
                .json("{\"to\":\"player@example.com\",\"secret\":\"" + canary + "\"}")
                .build();
        HttpRequest refused = HttpRequest.post(URI.create(StubHttpServer.refusedUri()
                        + "/users/sender@example.com/send?key=" + canary))
                .connectTimeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer " + canary)
                .json("{\"secret\":\"" + canary + "\"}")
                .build();

        for (HttpRequest request : new HttpRequest[] {dropped, refused}) {
            SendFailedException e = failing(request);
            assertThat(e.getMessage()).contains("/users/***/send failed ");
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).as(t.getClass().getName())
                        .doesNotContain(canary, "sender@", "player@");
            }
        }
    }

    @Test
    void usesTheJvmProxySettings() {
        final InetSocketAddress proxy = new InetSocketAddress(server.baseUri().getHost(), server.baseUri().getPort());
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return Collections.singletonList(new Proxy(Proxy.Type.HTTP, proxy));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
            }
        });
        try {
            // nothing listens on the target; only the "proxy" (the stub) can answer
            URI target = URI.create(StubHttpServer.refusedUri() + "/via-proxy");
            HttpResponse response = engine.execute(HttpRequest.post(target).connectTimeout(CONNECT_TIMEOUT)
                    .json("{}").build());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(server.lastRequest().path()).isEqualTo("/via-proxy");
        } finally {
            ProxySelector.setDefault(previous);
        }
    }
}
