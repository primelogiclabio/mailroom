package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
