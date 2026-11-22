package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void readsResendStyleHeaders() {
        RateLimit limit = RateLimit.of(response("RateLimit-Limit", "10", "RateLimit-Remaining", "0",
                "RateLimit-Reset", "1"), CLOCK).get();

        assertThat(limit.limit()).hasValue(10);
        assertThat(limit.remaining()).hasValue(0);
        assertThat(limit.resetIn()).contains(Duration.ofSeconds(1));
        assertThat(limit.exhausted()).isTrue();
        assertThat(limit.toString()).isEqualTo("RateLimit[limit 10, remaining 0, reset in PT1S]");
    }

    @Test
    void readsSendGridStyleHeadersWithAUnixTimeReset() {
        RateLimit limit = RateLimit.of(response("X-RateLimit-Limit", "600", "X-RateLimit-Remaining", "599",
                "X-RateLimit-Reset", String.valueOf(NOW.getEpochSecond() + 30)), CLOCK).get();

        assertThat(limit.limit()).hasValue(600);
        assertThat(limit.remaining()).hasValue(599);
        assertThat(limit.resetIn()).contains(Duration.ofSeconds(30));
        assertThat(limit.exhausted()).isFalse();

        RateLimit past = RateLimit.of(response("X-RateLimit-Reset", String.valueOf(NOW.getEpochSecond() - 5)),
                CLOCK).get();
        assertThat(past.resetIn()).contains(Duration.ZERO);
        assertThat(past.toString()).isEqualTo("RateLimit[limit ?, remaining ?, reset in PT0S]");
    }

    @Test
    void theUnprefixedHeadersWin() {
        RateLimit limit = RateLimit.of(response("X-RateLimit-Remaining", "5", "ratelimit-remaining", "0"), CLOCK)
                .get();
        assertThat(limit.remaining()).hasValue(0);
        assertThat(limit.limit()).isEmpty();
        assertThat(limit.resetIn()).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" reads as {1}")
    @CsvSource(delimiter = '|', value = {
            "10                        | 10",
            "' 7 '                     | 7",
            "'10, 10;w=1, 1000;w=3600' | 10",
            "100;w=60                  | 100",
            "1.2                       | 2",
            "999999999                 | 999999999",
    })
    void readsTheFirstNumber(String value, long expected) {
        RateLimit limit = RateLimit.of(response("RateLimit-Limit", value, "RateLimit-Reset", value), CLOCK).get();
        assertThat(limit.limit()).hasValue(expected);
        assertThat(limit.resetIn()).contains(Duration.ofSeconds(expected));
    }

    @Test
    void ignoresWhatItDoesNotUnderstand() {
        assertThat(RateLimit.of(response("RateLimit-Limit", "lots", "RateLimit-Remaining", "-1"), CLOCK)).isEmpty();
        assertThat(RateLimit.of(response("RateLimit-Limit", "10", "RateLimit-Remaining", "none"), CLOCK).get()
                .remaining()).isEmpty();
        assertThat(RateLimit.of(response("RateLimit", "\"default\";r=0;t=30"), CLOCK)).isEmpty();
        assertThat(RateLimit.of(response(), CLOCK)).isEmpty();
    }

    private static HttpResponse response(String... namesAndValues) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], Collections.singletonList(namesAndValues[i + 1]));
        }
        return HttpResponse.of(429, headers, new byte[0]);
    }
}
