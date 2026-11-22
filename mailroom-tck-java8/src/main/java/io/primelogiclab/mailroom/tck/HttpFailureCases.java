package io.primelogiclab.mailroom.tck;

import io.primelogiclab.mailroom.FailureKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The failure cases every HTTP transport shares, played by a {@link StubHttpServer}: the design's status table and the
 * failures after a request was sent. Responses carry an empty JSON object, so no provider error code applies and the
 * transport's defaults decide.
 *
 * <table>
 *   <caption>Standard cases</caption>
 *   <tr><th>Stub answer</th><th>Kind</th></tr>
 *   <tr><td>400</td><td>{@code INVALID_MESSAGE}</td></tr>
 *   <tr><td>401, 403</td><td>{@code AUTHENTICATION}</td></tr>
 *   <tr><td>409</td><td>{@code UNKNOWN_OUTCOME}</td></tr>
 *   <tr><td>429 with {@code Retry-After: 2}</td><td>{@code RATE_LIMITED}, retry-after 2 s</td></tr>
 *   <tr><td>500, 503</td><td>{@code UNAVAILABLE}</td></tr>
 *   <tr><td>504</td><td>{@code UNKNOWN_OUTCOME}</td></tr>
 *   <tr><td>connection closed after the request</td><td>{@code UNKNOWN_OUTCOME}</td></tr>
 * </table>
 *
 * <p>A read timeout needs the transport's own timeout, so it is a separate case: {@link #readTimeout}. A transport adds
 * its provider's error codes as further {@link FailureCase}s, and may leave out a standard case its provider documents
 * differently.
 *
 * @since 0.1.0
 */
public final class HttpFailureCases {

    private HttpFailureCases() {
    }

    /**
     * Returns the standard cases.
     *
     * @param server the stub the transport sends to
     * @return the cases, in the order of the table above; the list cannot be changed
     * @since 0.1.0
     */
    public static List<FailureCase> standard(StubHttpServer server) {
        List<FailureCase> cases = new ArrayList<FailureCase>();
        cases.add(status(server, 400, FailureKind.INVALID_MESSAGE));
        cases.add(status(server, 401, FailureKind.AUTHENTICATION));
        cases.add(status(server, 403, FailureKind.AUTHENTICATION));
        cases.add(status(server, 409, FailureKind.UNKNOWN_OUTCOME));
        cases.add(rateLimited(server, 2));
        cases.add(status(server, 500, FailureKind.UNAVAILABLE));
        cases.add(status(server, 503, FailureKind.UNAVAILABLE));
        cases.add(status(server, 504, FailureKind.UNKNOWN_OUTCOME));
        cases.add(droppedAfterRequest(server));
        return Collections.unmodifiableList(cases);
    }

    /**
     * A response with this status and an empty JSON object.
     *
     * @param server the stub
     * @param status the HTTP status
     * @param kind   the kind the transport must throw
     * @return the case, expecting the status too
     * @since 0.1.0
     */
    public static FailureCase status(final StubHttpServer server, final int status, FailureKind kind) {
        Objects.requireNonNull(server, "server");
        return FailureCase.of("HTTP " + status, new Runnable() {
            @Override
            public void run() {
                server.enqueue(StubResponse.status(status).json("{}"));
            }
        }, kind).status(status);
    }

    /**
     * A 429 with {@code Retry-After} in seconds.
     *
     * @param server            the stub
     * @param retryAfterSeconds the wait the response asks for
     * @return the case, expecting {@code RATE_LIMITED}, status 429 and that retry-after
     * @since 0.1.0
     */
    public static FailureCase rateLimited(final StubHttpServer server, final int retryAfterSeconds) {
        Objects.requireNonNull(server, "server");
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative");
        }
        return FailureCase.of("HTTP 429, Retry-After " + retryAfterSeconds, new Runnable() {
            @Override
            public void run() {
                server.enqueue(StubResponse.status(429).header("Retry-After", String.valueOf(retryAfterSeconds))
                        .json("{}"));
            }
        }, FailureKind.RATE_LIMITED).status(429).retryAfter(Duration.ofSeconds(retryAfterSeconds));
    }

    /**
     * The connection closes after the whole request arrived, with no response: the provider may have taken it.
     *
     * @param server the stub
     * @return the case, expecting {@code UNKNOWN_OUTCOME}
     * @since 0.1.0
     */
    public static FailureCase droppedAfterRequest(final StubHttpServer server) {
        Objects.requireNonNull(server, "server");
        return FailureCase.of("connection closed after the request", new Runnable() {
            @Override
            public void run() {
                server.enqueue(StubResponse.drop());
            }
        }, FailureKind.UNKNOWN_OUTCOME);
    }

    /**
     * A success that comes too late: the transport's read timeout must be shorter than the delay.
     *
     * @param server the stub
     * @param delay  how long the stub waits before answering; longer than the transport's read timeout
     * @return the case, expecting {@code UNKNOWN_OUTCOME}
     * @since 0.1.0
     */
    public static FailureCase readTimeout(final StubHttpServer server, final Duration delay) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(delay, "delay");
        return FailureCase.of("read timeout (answer after " + delay.toMillis() + " ms)", new Runnable() {
            @Override
            public void run() {
                server.enqueue(StubResponse.status(200).json("{}").delay(delay));
            }
        }, FailureKind.UNKNOWN_OUTCOME);
    }
}
