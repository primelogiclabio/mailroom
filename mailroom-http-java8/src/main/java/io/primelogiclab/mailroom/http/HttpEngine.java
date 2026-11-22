package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;

/**
 * Makes HTTP calls for a provider module. Get one from {@link HttpEngines#load()}: it finds an engine registered for
 * {@link java.util.ServiceLoader}, such as the {@code java.net.http} one from {@code mailroom-http-jdk11}, and falls
 * back to {@link HttpEngines#urlConnection() HttpURLConnection}.
 *
 * <pre>{@code
 * HttpResponse response;
 * try {
 *     response = engine.execute(request);
 * } catch (SendFailedException e) {
 *     throw e.toBuilder().transportName(name()).providerId("smtp2go").build();
 * }
 * }</pre>
 *
 * <p>Every HTTP status, 4xx and 5xx included, comes back as an {@link HttpResponse}. Only a failure to get a complete
 * response throws, as a {@link SendFailedException} already sorted by when it happened:
 * <ul>
 *   <li>{@link FailureKind#UNAVAILABLE} when the request can't have reached the provider: DNS, connect or TLS
 *       failure, or the connection failing before the whole body was handed over;</li>
 *   <li>{@link FailureKind#UNKNOWN_OUTCOME} once the whole request was sent: no response, a read timeout, a
 *       response cut short. The provider may have accepted the email.</li>
 * </ul>
 * The exception carries the I/O exception as its cause, and a message that names the method and address but never
 * the query, header values or body. It names no transport or provider; add them with
 * {@link SendFailedException#toBuilder()}.
 *
 * <p>Engines must be thread-safe, must not follow redirects, and must never re-send a POST or PUT on their own, since
 * a re-sent email can arrive twice. (GET and DELETE are idempotent; {@code HttpURLConnection} may retry them once.)
 *
 * @since 0.1.0
 */
public interface HttpEngine extends AutoCloseable {

    /**
     * Returns a short name for diagnostics.
     *
     * @return such as {@code urlconnection}
     * @since 0.1.0
     */
    String name();

    /**
     * Sends the request and reads the whole response.
     *
     * @param request the request
     * @return the response, whatever its status
     * @throws SendFailedException with kind {@code UNAVAILABLE} or {@code UNKNOWN_OUTCOME} if no complete response
     *                             arrived
     * @since 0.1.0
     */
    HttpResponse execute(HttpRequest request);

    /**
     * Releases what the engine holds. The transport that loaded the engine closes it. Does nothing by default.
     *
     * @since 0.1.0
     */
    @Override
    default void close() {
    }
}
