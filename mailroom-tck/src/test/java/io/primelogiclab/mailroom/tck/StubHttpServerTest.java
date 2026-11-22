package io.primelogiclab.mailroom.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the stub with a plain HttpURLConnection client, as HTTP-1's engine will. */
class StubHttpServerTest {

    private StubHttpServer server;

    @BeforeEach
    void start() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** What a client saw: status, one header and the body. */
    static final class Reply {
        final int status;
        final String contentType;
        final String retryAfter;
        final String body;

        Reply(int status, String contentType, String retryAfter, String body) {
            this.status = status;
            this.contentType = contentType;
            this.retryAfter = retryAfter;
            this.body = body;
        }
    }

    /**
     * Windows retries a refused loopback connect for about two seconds before reporting it, and with a shorter connect
     * timeout JDK 8 reports "connect timed out" instead, so the connect timeout is generous.
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;

    private static Reply post(URI uri, String body, int readTimeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Api-Key", "test-key-123");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(status, connection.getHeaderField("Content-Type"),
                    connection.getHeaderField("Retry-After"), in == null ? "" : read(in));
        } finally {
            connection.disconnect();
        }
    }

    private static Reply post(URI uri, String body) throws IOException {
        return post(uri, body, 2000);
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    @Test
    void recordsTheRequest() throws IOException {
        post(server.uri("/v3/email/send?region=eu&x=1"), "{\"to\":[\"player@example.com\"]}");

        assertThat(server.requestCount()).isEqualTo(1);
        RecordedRequest request = server.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v3/email/send");
        assertThat(request.query()).contains("region=eu&x=1");
        assertThat(request.header("x-api-key")).contains("test-key-123");
        assertThat(request.header("CONTENT-TYPE")).contains("application/json");
        assertThat(request.headers()).containsKey("x-api-key");
        assertThat(request.bodyAsString()).isEqualTo("{\"to\":[\"player@example.com\"]}");
        assertThat(request.body()).isEqualTo("{\"to\":[\"player@example.com\"]}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void playsTheScriptInOrderThenTheFallback() throws IOException {
        server.enqueue(StubResponse.status(503), StubResponse.status(429).header("Retry-After", "5"))
                .enqueue(StubResponse.status(202).json("{\"ok\":true}"));

        assertThat(post(server.uri("/send"), "1").status).isEqualTo(503);
        Reply limited = post(server.uri("/send"), "2");
        assertThat(limited.status).isEqualTo(429);
        assertThat(limited.retryAfter).isEqualTo("5");
        Reply accepted = post(server.uri("/send"), "3");
        assertThat(accepted.status).isEqualTo(202);
        assertThat(accepted.contentType).isEqualTo("application/json; charset=utf-8");
        assertThat(accepted.body).isEqualTo("{\"ok\":true}");
        assertThat(post(server.uri("/send"), "4").status).as("default fallback").isEqualTo(200);

        server.otherwise(StubResponse.status(500).body("down", "text/plain"));
        Reply down = post(server.uri("/send"), "5");
        assertThat(down.status).isEqualTo(500);
        assertThat(down.body).isEqualTo("down");
        assertThat(server.requestCount()).isEqualTo(5);
    }

    @Test
    void aDelayBeyondTheReadTimeoutTimesOutAfterTheRequestWasRecorded() {
        server.enqueue(StubResponse.status(200).delay(Duration.ofSeconds(3)));

        assertThatThrownBy(() -> post(server.uri("/send"), "slow", 200)).isInstanceOf(SocketTimeoutException.class);
        assertThat(server.requestCount()).isEqualTo(1);
        assertThat(server.lastRequest().bodyAsString()).isEqualTo("slow");
    }

    @Test
    void aDropFailsAfterTheRequestWasSent() {
        server.enqueue(StubResponse.drop());

        assertThatThrownBy(() -> post(server.uri("/send"), "gone")).isInstanceOf(IOException.class)
                .isNotInstanceOf(SocketTimeoutException.class);
        assertThat(server.requestCount()).isEqualTo(1);
        assertThat(server.lastRequest().bodyAsString()).isEqualTo("gone");
    }

    /**
     * HttpURLConnection hands back a body cut short by a closed connection without any error, so a client (HTTP-1's
     * engine) has to compare what it read with Content-Length itself.
     */
    @Test
    void aDropAfterSomeBytesCutsTheBodyShort() throws IOException {
        String json = "{\"data\":{\"email_id\":\"abc\"}}";
        server.enqueue(StubResponse.status(200).json(json).dropAfter(5));

        HttpURLConnection connection = (HttpURLConnection) server.uri("/send").toURL().openConnection();
        connection.setReadTimeout(2000);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            assertThat(connection.getContentLengthLong()).isEqualTo(json.length());
            String body;
            try {
                body = read(connection.getInputStream());
            } catch (IOException e) {
                body = null;    // some JDKs report the early end instead
            }
            if (body != null) {
                assertThat(body).isEqualTo(json.substring(0, 5));
            }
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void theNextRequestStillWorksAfterADrop() throws IOException {
        server.enqueue(StubResponse.drop(), StubResponse.status(201));

        assertThatThrownBy(() -> post(server.uri("/send"), "1")).isInstanceOf(IOException.class);
        assertThat(post(server.uri("/send"), "2").status).isEqualTo(201);
    }

    @Test
    void refusedUriRefusesConnections() {
        assertThatThrownBy(() -> post(StubHttpServer.refusedUri().resolve("/send"), "x"))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void aDelayedRequestDoesNotHoldUpTheNext() throws Exception {
        server.enqueue(StubResponse.status(200).delay(Duration.ofMillis(1500)), StubResponse.status(204));

        CompletableFuture<Reply> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return post(server.uri("/slow"), "slow", 5000);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.requestCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        long start = System.nanoTime();
        assertThat(post(server.uri("/fast"), "fast").status).isEqualTo(204);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        assertThat(slow.get(5, TimeUnit.SECONDS).status).isEqualTo(200);
    }

    @Test
    void resetForgetsRequestsScriptAndFallback() throws IOException {
        server.enqueue(StubResponse.status(500)).otherwise(StubResponse.status(503));
        post(server.uri("/send"), "x");
        server.enqueue(StubResponse.status(418));

        server.reset();

        assertThat(server.requestCount()).isZero();
        assertThat(post(server.uri("/send"), "y").status).isEqualTo(200);
    }

    @Test
    void refusesConnectionsOnceClosed() {
        URI uri = server.uri("/send");
        server.close();
        server.close();

        assertThatThrownBy(() -> post(uri, "x")).isInstanceOf(ConnectException.class);
        assertThat(server.toString()).endsWith(", closed]");
    }

    @Test
    void neverShowsHeaderValuesOrBodies() throws IOException {
        post(server.uri("/send?api_key=secret-in-query"), "player@example.com");

        String text = server.lastRequest().toString();
        assertThat(text).isEqualTo("POST /send?(query) (18 bytes)")
                .doesNotContain("test-key-123").doesNotContain("secret-in-query").doesNotContain("player");
    }

    @Test
    void lastRequestNeedsARequest() {
        assertThatThrownBy(() -> server.lastRequest()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checksItsArguments() {
        assertThatThrownBy(() -> StubResponse.status(99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StubResponse.status(600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StubResponse.status(200).delay(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StubResponse.status(200).json("{}").dropAfter(2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StubResponse.drop().dropAfter(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> server.uri("send")).isInstanceOf(IllegalArgumentException.class);
        assertThat(StubResponse.status(200).json("{}").json("[]").toString())
                .as("a second body replaces the content type").isEqualTo("StubResponse[200, 1 header, 2 bytes]");
    }
}
