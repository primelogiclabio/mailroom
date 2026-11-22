/**
 * The mailroom technology compatibility kit: contract tests that every transport module extends, covering message
 * mapping ({@link io.primelogiclab.mailroom.tck.MappingContract}), failure classification
 * ({@link io.primelogiclab.mailroom.tck.FailureContract}, with {@link io.primelogiclab.mailroom.tck.HttpFailureCases}
 * for HTTP providers), secret redaction ({@link io.primelogiclab.mailroom.tck.RedactionContract}) and precheck
 * ({@link io.primelogiclab.mailroom.tck.PrecheckContract}), over the canonical emails of
 * {@link io.primelogiclab.mailroom.tck.TckEmails}. They are JUnit 5 tests; the module's README has a full example.
 *
 * <p>{@link io.primelogiclab.mailroom.tck.StubHttpServer} is the server HTTP transports run them against. It runs on
 * the JDK's built-in
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
 * tests use the stub server.
 */
package io.primelogiclab.mailroom.tck;
