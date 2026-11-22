package io.primelogiclab.mailroom.tck;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a {@link StubHttpServer} does with one request: answer with a status, headers and body, optionally after a
 * delay, or drop the connection.
 *
 * <pre>{@code
 * StubResponse.status(202).json("{\"data\":{\"email_id\":\"abc\"}}")
 * StubResponse.status(429).header("Retry-After", "5")
 * StubResponse.status(200).delay(Duration.ofSeconds(2))     // the client's read timeout fires first
 * StubResponse.drop()                                        // request read, then no response at all
 * StubResponse.status(200).json(body).dropAfter(10)          // response cut off after 10 body bytes
 * }</pre>
 *
 * <p>Instances are immutable: every method returns a changed copy.
 *
 * @since 0.1.0
 */
public final class StubResponse {

    private final int status;
    private final List<String[]> headers;
    private final byte[] body;
    private final Duration delay;
    private final boolean drop;
    private final int dropAfter;

    private StubResponse(int status, List<String[]> headers, byte[] body, Duration delay, boolean drop,
            int dropAfter) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.delay = delay;
        this.drop = drop;
        this.dropAfter = dropAfter;
    }

    /**
     * Answers with this status and an empty body.
     *
     * @param status the HTTP status, from 100 to 599
     * @return the response
     * @throws IllegalArgumentException if the status is out of range
     * @since 0.1.0
     */
    public static StubResponse status(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be from 100 to 599: " + status);
        }
        return new StubResponse(status, Collections.<String[]>emptyList(), new byte[0], Duration.ZERO, false, -1);
    }

    /**
     * Reads the whole request, then closes the connection without sending a status line, as a server or proxy that
     * crashes mid-request would. A client sees the failure only after its request was sent.
     *
     * @return the response
     * @since 0.1.0
     */
    public static StubResponse drop() {
        return new StubResponse(0, Collections.<String[]>emptyList(), new byte[0], Duration.ZERO, true, -1);
    }

    /**
     * Adds a response header; call again to add more, including several values for one name.
     *
     * @param name  the header name
     * @param value the value
     * @return a copy with the header
     * @since 0.1.0
     */
    public StubResponse header(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        List<String[]> copy = new ArrayList<>(headers);
        copy.add(new String[] {name, value});
        return new StubResponse(status, Collections.unmodifiableList(copy), body, delay, drop, dropAfter);
    }

    /**
     * Sets a text body, encoded as UTF-8, and its {@code Content-Type}.
     *
     * @param text        the body
     * @param contentType such as {@code text/plain; charset=utf-8}
     * @return a copy with the body
     * @since 0.1.0
     */
    public StubResponse body(String text, String contentType) {
        return body(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), contentType);
    }

    /**
     * Sets a body and its {@code Content-Type}.
     *
     * @param bytes       the body
     * @param contentType the content type
     * @return a copy with the body
     * @since 0.1.0
     */
    public StubResponse body(byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(contentType, "contentType");
        List<String[]> withoutType = new ArrayList<>();
        for (String[] header : headers) {
            if (!"Content-Type".equalsIgnoreCase(header[0])) {
                withoutType.add(header);
            }
        }
        return new StubResponse(status, withoutType, bytes.clone(), delay, drop, -1)
                .header("Content-Type", contentType);
    }

    /**
     * Sets a JSON body with {@code Content-Type: application/json; charset=utf-8}.
     *
     * @param json the JSON text
     * @return a copy with the body
     * @since 0.1.0
     */
    public StubResponse json(String json) {
        return body(json, "application/json; charset=utf-8");
    }

    /**
     * Waits this long before answering or dropping, such as longer than a client's read timeout.
     *
     * @param delay the wait
     * @return a copy with the delay
     * @throws IllegalArgumentException if the delay is negative
     * @since 0.1.0
     */
    public StubResponse delay(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return new StubResponse(status, headers, body, delay, drop, dropAfter);
    }

    /**
     * Sends the status, the headers and a {@code Content-Length} for the whole body, then only the first
     * {@code bytes} bytes of the body, and closes the connection.
     *
     * @param bytes how many body bytes to send, less than the body's length
     * @return a copy that drops mid-response
     * @throws IllegalArgumentException if {@code bytes} is negative or not less than the body's length
     * @throws IllegalStateException    if this response is a {@link #drop()}
     * @since 0.1.0
     */
    public StubResponse dropAfter(int bytes) {
        if (drop) {
            throw new IllegalStateException("drop() sends no response to cut off");
        }
        if (bytes < 0 || bytes >= body.length) {
            throw new IllegalArgumentException("dropAfter needs 0 to " + (body.length - 1) + " bytes of a "
                    + body.length + "-byte body: " + bytes);
        }
        return new StubResponse(status, headers, body, delay, drop, bytes);
    }

    int statusCode() {
        return status;
    }

    List<String[]> headerList() {
        return headers;
    }

    byte[] bodyBytes() {
        return body;
    }

    Duration delayBefore() {
        return delay;
    }

    boolean drops() {
        return drop;
    }

    int bytesBeforeDrop() {
        return dropAfter;
    }

    /**
     * Describes the response, such as {@code StubResponse[429, 2 headers, 0 bytes]} or {@code StubResponse[drop]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        if (drop) {
            return "StubResponse[drop" + (delay.isZero() ? "" : " after " + delay.toMillis() + " ms") + "]";
        }
        return "StubResponse[" + status + ", " + headers.size() + (headers.size() == 1 ? " header, " : " headers, ")
                + body.length + " bytes" + (delay.isZero() ? "" : ", delay " + delay.toMillis() + " ms")
                + (dropAfter < 0 ? "" : ", drop after " + dropAfter + " bytes") + "]";
    }
}
