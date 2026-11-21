/**
 * Shared plumbing for HTTP-based mailroom providers: the {@code HttpEngine} interface and its {@code HttpURLConnection}
 * engine, timeouts, JSON reading and writing, {@code Retry-After} and rate-limit header parsing, and the default
 * mapping from HTTP status to failure kind.
 *
 * <p><strong>This is a support library for provider authors, not application API.</strong> Applications use
 * {@code mailroom-core}; this module may change in ways that only provider modules need to follow.
 *
 * <p>Nothing is here yet; the types arrive with the HTTP items of the implementation plan.
 */
package io.primelogiclab.mailroom.http;
