package io.primelogiclab.mailroom.http;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A provider's rate-limit headers: how many requests the window allows, how many are left and when it resets.
 *
 * <pre>{@code
 * Optional<RateLimit> limit = RateLimit.of(response, context.clock());
 * if (limit.isPresent() && limit.get().exhausted()) { ... }
 * }</pre>
 *
 * <p>Read from {@code RateLimit-Limit}, {@code RateLimit-Remaining} and {@code RateLimit-Reset} (as Resend sends
 * them), or, when none of those is there, the {@code X-RateLimit-*} headers (as SendGrid sends them). A reset of
 * 1,000,000,000 or more is a Unix time in seconds, as with SendGrid; a smaller one is seconds from now. A value listing
 * several policies, such as {@code 10, 10;w=1, 1000;w=3600}, is read by its first number. The structured
 * {@code RateLimit} header of later IETF drafts is not read.
 *
 * @since 0.1.0
 */
public final class RateLimit {

    /** From here on, a reset is a Unix time: as a delay it would be over 31 years. */
    private static final long EPOCH_THRESHOLD = 1000000000L;

    private static final Pattern NUMBER = Pattern.compile("\\s*(\\d{1,18})(?:\\.(\\d{1,9}))?\\s*(?:[,;].*)?",
            Pattern.DOTALL);

    private final OptionalLong limit;
    private final OptionalLong remaining;
    private final Duration resetIn;

    private RateLimit(OptionalLong limit, OptionalLong remaining, Duration resetIn) {
        this.limit = limit;
        this.remaining = remaining;
        this.resetIn = resetIn;
    }

    /**
     * Reads a response's rate-limit headers.
     *
     * @param response the response
     * @param clock    the clock a Unix-time reset is measured against, normally {@code SendContext.clock()}
     * @return the rate limit; empty if the response has none of the headers, or none it understands
     * @since 0.1.0
     */
    public static Optional<RateLimit> of(HttpResponse response, Clock clock) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(clock, "clock");
        RateLimit limit = read(response, "ratelimit-", clock);
        return Optional.ofNullable(limit != null ? limit : read(response, "x-ratelimit-", clock));
    }

    /**
     * Returns the number of requests the current window allows.
     *
     * @return the limit, if the response gave it
     * @since 0.1.0
     */
    public OptionalLong limit() {
        return limit;
    }

    /**
     * Returns the number of requests left in the current window.
     *
     * @return the count, if the response gave it
     * @since 0.1.0
     */
    public OptionalLong remaining() {
        return remaining;
    }

    /**
     * Returns the time until the window resets.
     *
     * @return the time, never negative, if the response gave it
     * @since 0.1.0
     */
    public Optional<Duration> resetIn() {
        return Optional.ofNullable(resetIn);
    }

    /**
     * Tells whether no requests are left in the current window.
     *
     * @return true if the remaining count is 0
     * @since 0.1.0
     */
    public boolean exhausted() {
        return remaining.isPresent() && remaining.getAsLong() == 0;
    }

    /**
     * Describes the rate limit, such as {@code RateLimit[limit 10, remaining 0, reset in PT1S]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "RateLimit[limit " + (limit.isPresent() ? String.valueOf(limit.getAsLong()) : "?")
                + ", remaining " + (remaining.isPresent() ? String.valueOf(remaining.getAsLong()) : "?")
                + ", reset in " + (resetIn != null ? resetIn.toString() : "?") + "]";
    }

    private static RateLimit read(HttpResponse response, String prefix, Clock clock) {
        OptionalLong limit = number(response, prefix + "limit");
        OptionalLong remaining = number(response, prefix + "remaining");
        OptionalLong reset = number(response, prefix + "reset");
        if (!limit.isPresent() && !remaining.isPresent() && !reset.isPresent()) {
            return null;
        }
        Duration resetIn = null;
        if (reset.isPresent()) {
            long r = reset.getAsLong();
            resetIn = r < EPOCH_THRESHOLD ? Duration.ofSeconds(r)
                    : Duration.ofSeconds(Math.max(0, r - clock.instant().getEpochSecond()));
        }
        return new RateLimit(limit, remaining, resetIn);
    }

    /** The header's first number, a fraction rounded up; empty if absent or not a number. */
    private static OptionalLong number(HttpResponse response, String header) {
        Optional<String> value = response.header(header);
        if (!value.isPresent()) {
            return OptionalLong.empty();
        }
        Matcher m = NUMBER.matcher(value.get());
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        if (m.group(2) == null) {
            return OptionalLong.of(Long.parseLong(m.group(1)));
        }
        // 18 digits rounded up still fit a long
        return OptionalLong.of(new BigDecimal(m.group(1) + "." + m.group(2)).setScale(0, RoundingMode.CEILING)
                .longValueExact());
    }
}
