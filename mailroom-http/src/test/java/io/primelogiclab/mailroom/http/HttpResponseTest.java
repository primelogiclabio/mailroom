package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpResponseTest {

    @Test
    void looksUpHeadersInAnyCaseAndKeepsValueOrder() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("X-RateLimit-Remaining", Collections.singletonList("0"));
        headers.put("Set-Cookie", Arrays.asList("a=1", "b=2"));
        headers.put("set-cookie", Collections.singletonList("c=3"));
        HttpResponse response = HttpResponse.of(429, headers, new byte[0]);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.isSuccessful()).isFalse();
        assertThat(response.header("x-ratelimit-remaining")).contains("0");
        assertThat(response.headers("SET-COOKIE")).containsExactly("a=1", "b=2", "c=3");
        assertThat(response.header("Retry-After")).isEmpty();
        assertThat(response.headers("Retry-After")).isEmpty();
        assertThat(response.headers().keySet()).containsExactly("set-cookie", "x-ratelimit-remaining");
        assertThatThrownBy(() -> response.headers().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.headers("set-cookie").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesTheBodyAndReadsItAsUtf8() {
        byte[] body = "{\"ok\":\"é\"}".getBytes(StandardCharsets.UTF_8);
        HttpResponse response = HttpResponse.of(200, Collections.<String, List<String>>emptyMap(), body);
        body[0] = 'x';
        assertThat(response.bodyAsString()).isEqualTo("{\"ok\":\"é\"}");
        response.body()[0] = 'x';
        assertThat(response.bodyAsString()).isEqualTo("{\"ok\":\"é\"}");
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.toString()).isEqualTo("HttpResponse[200, 11 bytes]");
        assertThat(HttpResponse.of(204, Collections.<String, List<String>>emptyMap(), new byte[1]).toString())
                .isEqualTo("HttpResponse[204, 1 byte]");
    }

    @Test
    void successMeansTwoHundreds() {
        Map<String, List<String>> none = Collections.emptyMap();
        assertThat(HttpResponse.of(199, none, new byte[0]).isSuccessful()).isFalse();
        assertThat(HttpResponse.of(299, none, new byte[0]).isSuccessful()).isTrue();
        assertThat(HttpResponse.of(302, none, new byte[0]).isSuccessful()).isFalse();
        assertThatThrownBy(() -> HttpResponse.of(99, none, new byte[0]))
                .hasMessage("statusCode must be from 100 to 999: 99");
        assertThatThrownBy(() -> HttpResponse.of(1000, none, new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsASuccessBodyAsJson() {
        HttpResponse response = response(200, "{\"data\":{\"email_id\":\"m-1\",\"added_later\":[1]}}");
        assertThat(response.json().get("data").get("email_id").asString()).contains("m-1");
        assertThat(response.tryJson().get().get("data").get("email_id").asString()).contains("m-1");
    }

    @Test
    void anUnreadableSuccessBodyIsAnUnknownOutcome() {
        String canary = "canary.person@example.com";
        assertUnknownOutcome(response(200, ""), "the 200 response body is empty");
        assertUnknownOutcome(response(202, "{\"to\":\"" + canary + "\""),
                "the 202 response body ends before the JSON value is complete");
        assertUnknownOutcome(response(200, "OK " + canary), "the 200 response body is not valid JSON (line 1, column ");
        assertUnknownOutcome(response(500, "<html>" + canary + "</html>"),
                "the 500 response body is not valid JSON (line 1, column ");
    }

    @Test
    void tryJsonIsEmptyForBodiesThatAreNotJson() {
        assertThat(response(502, "<html><body>Bad Gateway</body></html>").tryJson()).isEmpty();
        assertThat(response(503, "").tryJson()).isEmpty();
        assertThat(response(400, "{\"errors\":[{\"message\":\"bad").tryJson()).isEmpty();
        assertThat(response(429, "{\"message\":\"slow down\"}").tryJson().get().get("message").asString())
                .contains("slow down");
    }

    private static HttpResponse response(int status, String body) {
        return HttpResponse.of(status, Collections.<String, List<String>>emptyMap(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertUnknownOutcome(HttpResponse response, String messageStart) {
        assertThatThrownBy(response::json)
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
                    assertThat(e.statusCode()).hasValue(response.statusCode());
                    assertThat(e.getMessage()).startsWith("UNKNOWN_OUTCOME: " + messageStart)
                            .doesNotContain("canary", "example");
                    assertThat(e.getCause()).isNull();
                    assertThat(e.transportName()).isEmpty();
                });
    }
}
