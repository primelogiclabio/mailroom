package io.primelogiclab.mailroom.tck;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One request a {@link StubHttpServer} received: method, path, query, headers and body.
 *
 * <p>{@link #toString()} never shows header values or the body, because headers carry API keys and bodies carry
 * addresses.
 *
 * @since 0.1.0
 */
public final class RecordedRequest {

    private final String method;
    private final String path;
    private final String query;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    RecordedRequest(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.query = query;
        Map<String, List<String>> lower = new TreeMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            List<String> values = lower.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                lower.put(name, values);
            }
            values.addAll(entry.getValue());
        }
        for (Map.Entry<String, List<String>> entry : lower.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        this.headers = Collections.unmodifiableMap(lower);
        this.body = body;
    }

    /**
     * Returns the request method.
     *
     * @return such as {@code POST}
     * @since 0.1.0
     */
    public String method() {
        return method;
    }

    /**
     * Returns the request path, without the query.
     *
     * @return such as {@code /v3/email/send}
     * @since 0.1.0
     */
    public String path() {
        return path;
    }

    /**
     * Returns the raw query string, if the request had one.
     *
     * @return such as {@code a=1&b=2}, without the {@code ?}
     * @since 0.1.0
     */
    public Optional<String> query() {
        return Optional.ofNullable(query);
    }

    /**
     * Returns the first value of a header.
     *
     * @param name the header name, in any case
     * @return the value, if the request had the header
     * @since 0.1.0
     */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.<String>empty() : Optional.of(values.get(0));
    }

    /**
     * Returns every header.
     *
     * @return values by lower-case header name; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Returns the request body.
     *
     * @return a copy of the body; empty if there was none
     * @since 0.1.0
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the request body as UTF-8 text.
     *
     * @return the body
     * @since 0.1.0
     */
    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Describes the request without header values or the body, such as {@code POST /v3/email/send (512 bytes)}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return method + " " + path + (query == null ? "" : "?(query)") + " (" + body.length + " bytes)";
    }
}
