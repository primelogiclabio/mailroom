package io.primelogiclab.mailroom.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What an {@link HttpEngine} got back: status, headers and body. Every status comes back this way, errors included;
 * turning a status into a failure is the provider's job. Immutable.
 *
 * <p>{@link #toString()} shows only the status and body length, because error bodies often repeat addresses.
 *
 * @since 0.1.0
 */
public final class HttpResponse {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    private HttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }

    /**
     * Creates a response, for engines and for provider tests.
     *
     * @param statusCode the HTTP status, from 100 to 999
     * @param headers    values by header name, in any case; names that differ only in case are merged, values keep
     *                   their order
     * @param body       the body; copied
     * @return the response
     * @throws IllegalArgumentException if the status is out of range
     * @since 0.1.0
     */
    public static HttpResponse of(int statusCode, Map<String, List<String>> headers, byte[] body) {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("statusCode must be from 100 to 999: " + statusCode);
        }
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Map<String, List<String>> lower = new TreeMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "header name").toLowerCase(Locale.ROOT);
            List<String> values = lower.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                lower.put(name, values);
            }
            for (String value : Objects.requireNonNull(entry.getValue(), "header values")) {
                values.add(Objects.requireNonNull(value, "header value"));
            }
        }
        for (Map.Entry<String, List<String>> entry : lower.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return new HttpResponse(statusCode, Collections.unmodifiableMap(lower), body.clone());
    }

    /**
     * Returns the HTTP status.
     *
     * @return such as {@code 200} or {@code 503}
     * @since 0.1.0
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Tells whether the status is 2xx.
     *
     * @return true for 200 to 299
     * @since 0.1.0
     */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Returns the first value of a header.
     *
     * @param name the header name, in any case
     * @return the value, if the response had the header
     * @since 0.1.0
     */
    public Optional<String> header(String name) {
        List<String> values = headers(name);
        return values.isEmpty() ? Optional.<String>empty() : Optional.of(values.get(0));
    }

    /**
     * Returns every value of a header, in the order received.
     *
     * @param name the header name, in any case
     * @return the values; empty if the response didn't have the header; the list cannot be changed
     * @since 0.1.0
     */
    public List<String> headers(String name) {
        List<String> values = headers.get(Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT));
        return values == null ? Collections.<String>emptyList() : values;
    }

    /**
     * Returns every header.
     *
     * @return values by lower-case header name, sorted by name; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Returns the body.
     *
     * @return a copy of the body; empty if there was none
     * @since 0.1.0
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the body as UTF-8 text, the encoding JSON uses.
     *
     * @return the body
     * @since 0.1.0
     */
    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Describes the response without its headers or body, such as {@code HttpResponse[503, 120 bytes]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "HttpResponse[" + statusCode + ", " + body.length + (body.length == 1 ? " byte]" : " bytes]");
    }
}
