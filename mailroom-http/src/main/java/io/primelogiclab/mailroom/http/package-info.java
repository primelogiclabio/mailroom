/**
 * Shared plumbing for HTTP-based mailroom providers: {@link io.primelogiclab.mailroom.http.HttpEngine}, found with
 * {@link io.primelogiclab.mailroom.http.HttpEngines#load()}, which falls back to an engine on
 * {@code java.net.http.HttpClient} (the {@code HttpURLConnection} engine remains as {@code HttpEngines.urlConnection()}),
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
 * <p><strong>Logging:</strong> nothing here logs header values. The default {@code HttpClient} engine logs headers
 * only when {@code -Djdk.httpclient.HttpClient.log} asks it to, but the JDK's {@code HttpURLConnection}, which the
 * {@code HttpEngines.urlConnection()} engine uses, logs every request header, credentials included, at {@code FINE} on the
 * {@code java.util.logging} logger {@code sun.net.www.protocol.http.HttpURLConnection}. So, as a deliberate side
 * effect, the engine sets that one logger to {@code INFO} while its level is inherited (not set by the application),
 * and sets it again if a logging reconfiguration clears it. Turning on {@code FINE} at the root, or DEBUG through an
 * SLF4J bridge, then shows no credentials. A level the application set on that logger itself is left alone; at
 * {@code FINE} or below, one {@code WARNING} on {@code io.primelogiclab.mailroom.http} says that credentials are being
 * logged.
 */
package io.primelogiclab.mailroom.http;
