package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HttpRequestTest {

    private static final URI API = URI.create("https://api.example.com/v3/email/send");

    @Test
    void buildsAJsonPost() throws IOException {
        HttpRequest request = HttpRequest.post(API)
                .header("X-Api-Key", "k-123")
                .header("Accept", "application/json")
                .json("{\"to\":\"é\"}")
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofMillis(1500))
                .build();

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo(API);
        assertThat(request.headers().keySet()).containsExactly("X-Api-Key", "Accept", "Content-Type");
        assertThat(request.header("content-type")).contains("application/json; charset=utf-8");
        assertThat(request.header("x-api-key")).contains("k-123");
        assertThat(request.header("Missing")).isEmpty();
        assertThat(request.hasBody()).isTrue();
        byte[] expected = "{\"to\":\"é\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(request.body()).isEqualTo(expected);
        assertThat(request.bodyLength()).isEqualTo(expected.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        request.writeBody(out);
        assertThat(out.toByteArray()).isEqualTo(expected);
        assertThat(request.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(request.readTimeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void hasDefaultsAndAnEmptyBodyForPostAndPut() {
        HttpRequest post = HttpRequest.post(API).build();
        assertThat(post.hasBody()).isTrue();
        assertThat(post.bodyLength()).isZero();
        assertThat(post.headers()).isEmpty();
        assertThat(post.connectTimeout()).isEqualTo(HttpRequest.DEFAULT_CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(post.readTimeout()).isEqualTo(HttpRequest.DEFAULT_READ_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
        assertThat(HttpRequest.put(API).build().method()).isEqualTo("PUT");
        assertThat(HttpRequest.put(API).build().hasBody()).isTrue();
    }

    @Test
    void getAndDeleteHaveNoBody() {
        HttpRequest get = HttpRequest.get(API).build();
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.hasBody()).isFalse();
        assertThat(get.body()).isEmpty();
        assertThat(HttpRequest.delete(API).build().hasBody()).isFalse();
        assertThatThrownBy(() -> HttpRequest.get(API).json("{}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("GET requests have no body");
        assertThatThrownBy(() -> HttpRequest.delete(API).body("text/plain", new byte[1]))
                .hasMessage("DELETE requests have no body");
    }

    @Test
    void repeatedHeadersJoinOneEntryWhateverTheCase() {
        HttpRequest request = HttpRequest.post(API).header("X-Tag", "a").header("x-tag", "b").build();
        assertThat(request.headers()).containsOnlyKeys("X-Tag");
        assertThat(request.headers().get("X-Tag")).containsExactly("a", "b");
        assertThatThrownBy(() -> request.headers().get("X-Tag").add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.headers().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLaterBodyReplacesTheEarlierOneAndItsContentType() {
        byte[] bytes = {1, 2, 3};
        HttpRequest request = HttpRequest.put(API).json("{}").body("application/octet-stream", bytes).build();
        bytes[0] = 9;
        assertThat(request.headers().get("Content-Type")).containsExactly("application/octet-stream");
        assertThat(request.body()).containsExactly(1, 2, 3);
        request.body()[1] = 9;
        assertThat(request.body()).containsExactly(1, 2, 3);
    }

    @Test
    void acceptsHttpsAndPlainHttpToLoopbackLiterals() {
        for (String uri : Arrays.asList("https://api.example.com", "HTTPS://api.example.com:8443/x?a=1",
                "https://127.0.0.1/x", "http://127.0.0.1:8080/x", "http://127.255.0.9/x", "http://localhost:1/x",
                "http://LOCALHOST/x", "http://[::1]:9/x", "http://[0:0:0:0:0:0:0:1]/x")) {
            assertThat(HttpRequest.post(URI.create(uri)).build().uri()).as(uri).isEqualTo(URI.create(uri));
        }
    }

    @Test
    void rejectsPlainHttpToOtherHosts() {
        for (String uri : Arrays.asList("http://api.example.com/x", "http://10.0.0.1/x",
                "http://localhost.example.com/x", "http://[::2]/x", "http://0.0.0.0/x")) {
            assertThatThrownBy(() -> HttpRequest.post(URI.create(uri))).as(uri)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("plain http is only allowed to a loopback host");
        }
        // java.net.URI already refuses 127.0.0.256 as a host; the check stands on its own too
        assertThat(HttpRequest.isLoopbackLiteral("127.0.0.256")).isFalse();
        assertThat(HttpRequest.isLoopbackLiteral("127.0.0.255")).isTrue();
    }

    @Test
    void rejectsOtherSchemesUserInfoAndFragments() {
        assertThatThrownBy(() -> HttpRequest.post(URI.create("ftp://example.com/x")))
                .hasMessage("the address must be absolute https (or http to a loopback host), not ftp:");
        assertThatThrownBy(() -> HttpRequest.post(URI.create("/v3/email/send")))
                .hasMessageEndingWith("not a relative address");
        assertThatThrownBy(() -> HttpRequest.post(URI.create("https://user:secret@example.com/x")))
                .hasMessage("the address must not hold user info; send credentials in a header")
                .hasMessageNotContaining("secret@");
        assertThatThrownBy(() -> HttpRequest.post(URI.create("https://example.com/x#part")))
                .hasMessage("the address must not have a fragment");
        assertThatThrownBy(() -> HttpRequest.post(URI.create("https://under_score/x")))
                .hasMessage("the address has no valid host");
    }

    @Test
    void rejectsBadHeaders() {
        HttpRequest.Builder b = HttpRequest.post(API);
        assertThatThrownBy(() -> b.header("Bad Name", "v")).hasMessage("the header name is not an HTTP token");
        assertThatThrownBy(() -> b.header("", "v")).hasMessage("the header name is not an HTTP token");
        assertThatThrownBy(() -> b.header("X-Key", "v\r\nX-Injected: 1"))
                .hasMessage("the value of the X-Key header has a control character (CR, LF or similar)")
                .hasMessageNotContaining("Injected");
        assertThatThrownBy(() -> b.header("X-Key", "v\u0000")).isInstanceOf(IllegalArgumentException.class);
        assertThat(b.header("X-Key", "tab\tis fine")).isSameAs(b);
        for (String owned : Arrays.asList("Host", "content-length", "Connection", "Transfer-Encoding", "Expect")) {
            assertThatThrownBy(() -> b.header(owned, "x")).as(owned)
                    .hasMessage("the engine sets the " + owned + " header itself");
        }
        assertThatThrownBy(() -> b.header("Content-Type", "text/plain"))
                .hasMessage("set Content-Type with body(contentType, bytes) or json(text)");
        assertThatThrownBy(() -> b.body(" ", new byte[0]))
                .hasMessage("the content type must be non-empty, without control characters");
    }

    @Test
    void rejectsTimeoutsThatWouldMeanForever() {
        HttpRequest.Builder b = HttpRequest.post(API);
        for (Duration bad : Arrays.asList(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-1),
                Duration.ofMillis(Integer.MAX_VALUE + 1L))) {
            assertThatThrownBy(() -> b.connectTimeout(bad)).as(bad.toString())
                    .hasMessage("connectTimeout must be from 1 ms to 2147483647 ms");
            assertThatThrownBy(() -> b.readTimeout(bad)).hasMessage("readTimeout must be from 1 ms to 2147483647 ms");
        }
        assertThat(b.readTimeout(Duration.ofMillis(1)).build().readTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void summaryAndToStringHideValuesQueryAndBody() {
        String canary = "canary-7f3a";
        HttpRequest request = HttpRequest.post(URI.create(
                        "https://graph.example.com:8443/v1.0/users/sender@example.com/sendMail?key=" + canary))
                .header("Authorization", "Bearer " + canary)
                .json("{\"secret\":\"" + canary + "\"}")
                .build();

        assertThat(request.summary()).isEqualTo("POST https://graph.example.com:8443/v1.0/users/***/sendMail");
        assertThat(request.toString()).isEqualTo("POST https://graph.example.com:8443/v1.0/users/***/sendMail?(query)"
                + " (24 bytes, headers: Authorization, Content-Type)");
        assertThat(request.toString()).doesNotContain(canary, "sender");
        assertThat(HttpRequest.get(URI.create("https://example.com")).build().toString())
                .isEqualTo("GET https://example.com/ (no body)");
        // an encoded @ is decoded before masking
        assertThat(HttpRequest.get(URI.create("https://example.com/users/a%40b.com/x/")).build().summary())
                .isEqualTo("GET https://example.com/users/***/x/");
    }
}
