/**
 * The mailroom technology compatibility kit: contract tests that every transport module runs against a local stub
 * HTTP server, covering message mapping, failure classification, secret redaction and precheck.
 *
 * <p>{@link io.primelogiclab.mailroom.tck.StubHttpServer} is that server. It runs on the JDK's built-in
 * {@code com.sun.net.httpserver}, records each request and plays a script of
 * {@link io.primelogiclab.mailroom.tck.StubResponse}s, including slow answers and dropped connections. It needs no
 * test framework. In a JUnit 5 test, start one per test and close it, or use try-with-resources:
 *
 * <pre>{@code
 * try (StubHttpServer server = StubHttpServer.start()) {
 *     server.enqueue(StubResponse.status(200).delay(Duration.ofSeconds(2)));
 *     // send through a transport with a 200 ms read timeout, pointed at server.baseUri():
 *     // the send fails as UNKNOWN_OUTCOME, and the request was still recorded
 *     assertThat(server.lastRequest().path()).isEqualTo("/v3/email/send");
 * }
 * }</pre>
 *
 * <p>This module depends on {@code mailroom-core} only, and must never depend on {@code mailroom-http}, whose own
 * tests use the stub server. The contract test base classes arrive with TCK-2 of the implementation plan.
 */
package io.primelogiclab.mailroom.tck;
