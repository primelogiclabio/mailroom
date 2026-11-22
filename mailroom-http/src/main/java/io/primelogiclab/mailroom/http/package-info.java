/**
 * Shared plumbing for HTTP-based mailroom providers: {@link io.primelogiclab.mailroom.http.HttpEngine}, found with
 * {@link io.primelogiclab.mailroom.http.HttpEngines#load()}, which falls back to an {@code HttpURLConnection} engine,
 * plus {@link io.primelogiclab.mailroom.http.HttpRequest} and {@link io.primelogiclab.mailroom.http.HttpResponse};
 * {@link io.primelogiclab.mailroom.http.JsonWriter} for request bodies and
 * {@link io.primelogiclab.mailroom.http.JsonValue}, a tolerant reader for responses, both on jackson-core's streaming
 * API, which never appears in this module's API; and {@link io.primelogiclab.mailroom.http.FailureMapper}, which turns
 * an error response into a {@code SendFailedException} by a default status table that each provider adjusts, reading
 * {@link io.primelogiclab.mailroom.http.RetryAfter} and {@link io.primelogiclab.mailroom.http.RateLimit} headers.
 *
 * <p><strong>This is a support library for provider authors, not application API.</strong> Applications use
 * {@code mailroom-core}; this module may change in ways that only provider modules need to follow.
 *
 * <p><strong>Logging:</strong> nothing here logs header values, but the JDK's {@code HttpURLConnection}, which the
 * default engine uses, logs every request header, credentials included, at {@code FINE} on the
 * {@code sun.net.www.protocol.http.HttpURLConnection} logger. Applications should keep that logger at {@code INFO} or
 * above; transports should say so in their documentation.
 */
package io.primelogiclab.mailroom.http;
