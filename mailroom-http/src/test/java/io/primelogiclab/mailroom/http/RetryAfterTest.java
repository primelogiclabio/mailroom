package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterTest {

    /** A Sunday. */
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    @ParameterizedTest(name = "\"{0}\" waits {1}")
    @CsvSource(delimiter = '|', value = {
            "0                                | PT0S",
            "120                              | PT2M",
            "'  5  '                          | PT5S",
            "1.5                              | PT1.5S",
            "0.0001                           | PT0.001S",
            "Sun, 22 Nov 2026 10:02:00 GMT    | PT2M",
            "Sun, 22 Nov 2026 10:02:00 +0000  | PT2M",
            "sun, 22 nov 2026 10:02:00 gmt    | PT2M",
            "Sunday, 22-Nov-26 10:02:00 GMT   | PT2M",
            "Sun Nov 22 10:02:00 2026         | PT2M",
            "Tue Dec  1 10:00:00 2026         | PT216H",
            "Sun, 06 Nov 1994 08:49:37 GMT    | PT0S",
            "Sun, 22 Nov 2026 10:00:00 GMT    | PT0S",
            "Friday, 01-Jan-77 00:00:00 GMT   | PT0S",
    })
    void readsSecondsAndEveryHttpDateForm(String value, String wait) {
        assertThat(RetryAfter.parse(value, NOW)).contains(Duration.parse(wait));
    }

    @Test
    void aTwoDigitYearUpTo50YearsAheadIsInThisCentury() {
        assertThat(RetryAfter.parse("Wednesday, 01-Jan-76 00:00:00 GMT", NOW).get())
                .isEqualTo(Duration.between(NOW, Instant.parse("2076-01-01T00:00:00Z")));
    }

    @Test
    void takesTheLargestNumberThatFits() {
        assertThat(RetryAfter.parse("999999999999999999", NOW)).contains(Duration.ofSeconds(999999999999999999L));
        assertThat(RetryAfter.parse("999999999999999999.5", NOW)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" is ignored")
    @ValueSource(strings = {"", " ", "-1", "+5", "soon", "1e3", "12abc", "1.", ".5", "1,000", "1234567890123456789",
            "Tuesday, 31-Feb-26 10:00:00 GMT", "Sun Foo 22 10:02:00 2026", "Sun, 22 Nov 2026 25:00:00 GMT",
            "22 Nov 2026"})
    void ignoresWhatItDoesNotUnderstand(String value) {
        assertThat(RetryAfter.parse(value, NOW)).isEmpty();
    }

    @Test
    void readsTheFirstHeaderAgainstTheClock() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThat(RetryAfter.of(response("retry-after", Arrays.asList("3", "60")), clock)).contains(Duration.ofSeconds(3));
        assertThat(RetryAfter.of(response("Retry-After", Collections.singletonList("Sun, 22 Nov 2026 10:00:30 GMT")),
                clock)).contains(Duration.ofSeconds(30));
        assertThat(RetryAfter.of(response("X-Other", Collections.singletonList("3")), clock)).isEmpty();
    }

    private static HttpResponse response(String header, List<String> values) {
        Map<String, List<String>> headers = Collections.singletonMap(header, values);
        return HttpResponse.of(429, headers, "{}".getBytes(StandardCharsets.UTF_8));
    }
}
