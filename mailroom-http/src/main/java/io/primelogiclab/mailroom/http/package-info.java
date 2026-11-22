/**
 * Shared plumbing for HTTP-based mailroom providers: {@link io.primelogiclab.mailroom.http.HttpEngine}, found with
 * {@link io.primelogiclab.mailroom.http.HttpEngines#load()}, which falls back to an {@code HttpURLConnection} engine,
 * plus {@link io.primelogiclab.mailroom.http.HttpRequest} and {@link io.primelogiclab.mailroom.http.HttpResponse}.
 * JSON reading and writing, {@code Retry-After} and rate-limit header parsing, and the default mapping from HTTP
 * status to failure kind come next.
 *
 * <p><strong>This is a support library for provider authors, not application API.</strong> Applications use
 * {@code mailroom-core}; this module may change in ways that only provider modules need to follow.
 */
package io.primelogiclab.mailroom.http;
