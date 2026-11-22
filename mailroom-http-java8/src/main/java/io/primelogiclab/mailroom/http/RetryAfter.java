package io.primelogiclab.mailroom.http;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code Retry-After} header: how long a provider asks a client to wait, in seconds or as an HTTP date.
 *
 * <pre>{@code
 * Optional<Duration> wait = RetryAfter.of(response, context.clock());
 * }</pre>
 *
 * <p>Accepted forms, per RFC 9110:
 * <ul>
 *   <li>seconds, such as {@code 120}; a decimal such as {@code 1.5} is tolerated and rounded up to the millisecond;</li>
 *   <li>an IMF-fixdate, such as {@code Sun, 06 Nov 1994 08:49:37 GMT};</li>
 *   <li>the obsolete RFC 850 form, such as {@code Sunday, 06-Nov-94 08:49:37 GMT}, whose two-digit year is read as
 *       the most recent matching year not more than 50 years ahead;</li>
 *   <li>the asctime form, such as {@code Sun Nov  6 08:49:37 1994}.</li>
 * </ul>
 * A date in the past means no wait. A negative number, a number of more than 18 digits, or anything else is ignored.
 *
 * @since 0.1.0
 */
public final class RetryAfter {

    private static final Pattern SECONDS = Pattern.compile("(\\d{1,18})(?:\\.(\\d{1,9}))?");
    private static final Pattern RFC_850 = Pattern.compile(
            "[A-Za-z]+, (\\d{2})-([A-Za-z]{3})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) GMT");
    private static final Pattern ASCTIME = Pattern.compile(
            "[A-Za-z]{3} ([A-Za-z]{3}) ( \\d|\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\d{4})");
    private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    private RetryAfter() {
    }

    /**
     * Reads a response's {@code Retry-After} header; with more than one, the first.
     *
     * @param response the response
     * @param clock    the clock an HTTP date is measured against, normally {@code SendContext.clock()}
     * @return the wait; empty if the header is absent or not understood
     * @since 0.1.0
     */
    public static Optional<Duration> of(HttpResponse response, Clock clock) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(clock, "clock");
        Optional<String> value = response.header("Retry-After");
        return value.isPresent() ? parse(value.get(), clock.instant()) : Optional.<Duration>empty();
    }

    /**
     * Reads a {@code Retry-After} value.
     *
     * @param value the header value; surrounding whitespace is ignored
     * @param now   the time an HTTP date is measured from
     * @return the wait, never negative; empty if the value is not understood
     * @since 0.1.0
     */
    public static Optional<Duration> parse(String value, Instant now) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(now, "now");
        String v = value.trim();
        Matcher seconds = SECONDS.matcher(v);
        if (seconds.matches()) {
            return seconds(seconds.group(1), seconds.group(2));
        }
        Instant date = date(v, now);
        if (date == null) {
            return Optional.empty();
        }
        return Optional.of(date.isAfter(now) ? Duration.between(now, date) : Duration.ZERO);
    }

    private static Optional<Duration> seconds(String whole, String fraction) {
        if (fraction == null) {
            return Optional.of(Duration.ofSeconds(Long.parseLong(whole)));
        }
        BigDecimal millis = new BigDecimal(whole + "." + fraction).movePointRight(3).setScale(0, RoundingMode.CEILING);
        try {
            return Optional.of(Duration.ofMillis(millis.longValueExact()));
        } catch (ArithmeticException e) {
            // 18 digits of seconds plus a fraction overflows a long of milliseconds
            return Optional.empty();
        }
    }

    /** The date in any of the three HTTP forms, or null. */
    private static Instant date(String v, Instant now) {
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(v));
        } catch (DateTimeException e) {
            // not an IMF-fixdate; try the obsolete forms
        }
        try {
            Matcher m = RFC_850.matcher(v);
            if (m.matches()) {
                int year = twoDigitYear(Integer.parseInt(m.group(3)), now);
                return at(year, m.group(2), m.group(1), m.group(4), m.group(5), m.group(6));
            }
            m = ASCTIME.matcher(v);
            if (m.matches()) {
                return at(Integer.parseInt(m.group(6)), m.group(1), m.group(2).trim(), m.group(3), m.group(4),
                        m.group(5));
            }
        } catch (DateTimeException e) {
            // a day or time out of range, such as 31-Feb
        }
        return null;
    }

    /** RFC 9110: a two-digit year more than 50 years ahead means the century before. */
    private static int twoDigitYear(int yy, Instant now) {
        int current = now.atZone(ZoneOffset.UTC).getYear();
        int year = current - current % 100 + yy;
        return year > current + 50 ? year - 100 : year;
    }

    private static Instant at(int year, String month, String day, String hour, String minute, String second) {
        int index = MONTHS.indexOf(month.toLowerCase(Locale.ROOT));
        if (index < 0 || index % 3 != 0) {
            return null;
        }
        return LocalDateTime.of(year, index / 3 + 1, Integer.parseInt(day), Integer.parseInt(hour),
                Integer.parseInt(minute), Integer.parseInt(second)).toInstant(ZoneOffset.UTC);
    }
}
