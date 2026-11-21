package io.primelogiclab.mailroom.internal;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads durations from configuration text. Not API: this package may change in any release.
 */
public final class Durations {

    private static final Pattern SHORT = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    private Durations() {
    }

    /**
     * Reads {@code 250ms}, {@code 30s}, {@code 5m}, {@code 1h}, {@code 2d} or ISO-8601 ({@code PT30S}), in any letter
     * case.
     *
     * @param text the text
     * @return the duration, or null if the text is not a duration or is negative
     */
    public static Duration parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        Duration duration = null;
        try {
            Matcher m = SHORT.matcher(s);
            if (m.matches()) {
                long amount = Long.parseLong(m.group(1));
                String unit = m.group(2);
                duration = "ms".equals(unit) ? Duration.ofMillis(amount)
                        : "s".equals(unit) ? Duration.ofSeconds(amount)
                        : "m".equals(unit) ? Duration.ofMinutes(amount)
                        : "h".equals(unit) ? Duration.ofHours(amount)
                        : Duration.ofDays(amount);
            } else if (s.startsWith("p")) {
                duration = Duration.parse(s.toUpperCase(Locale.ROOT));
            }
        } catch (NumberFormatException e) {
            return null;
        } catch (ArithmeticException e) {
            return null;
        } catch (DateTimeParseException e) {
            return null;
        }
        return duration == null || duration.isNegative() ? null : duration;
    }
}
