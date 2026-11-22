# Changelog

All notable changes to mailroom are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Module skeleton on mailroom-parent 0.1.0: `mailroom-core`, `mailroom-http`, `mailroom-test` and `mailroom-tck`,
  each with its `Automatic-Module-Name` and a smoke test, built as Java 8 bytecode and tested on JDK 8, 21 and 25.
- CI and release workflows from mailroom-parent's templates; snapshots deploy from `main`.
- `mailroom-core` message model: `Address` (parsing, formatting, masked `toString`), `Attachment` (regular and
  inline `cid:` parts) and the immutable `Email` with its builder, checked when built: recipients, subject, bodies,
  inline content ids, custom headers (no CR/LF, no reserved names), tags, metadata, `sendAt` and `idempotencyKey`.
- `MailroomException` (unchecked) and `InvalidEmailException`, which lists every problem and never repeats an address,
  subject or body.
- `EmailExtension` for provider-specific options (`essential`, `providesContent`), attached with `Email.Builder.with`
  and read with `Email.extension(Class)`; a content-providing extension makes the subject and bodies optional.
- `spi.Capability`, an open set of feature keys with the well-known constants, and `spi.TransportCapabilities`, which
  declares what a transport supports and understands and its documented limits.
- Results and failures: `SendResult` (transport, provider, message id, `Status`, accepted time, attempts), `Attempt`,
  the `FailureKind` classification, `SendFailedException` (kind, transport, status and provider codes, retry-after,
  attempts) and `NoEligibleTransportException` (why each transport was skipped). Addresses in their messages are
  masked.
- The provider SPI in `spi`: `MailTransport` (with default `precheck`, `sendAsync`, `sendBatch` and `close`),
  `TransportProvider`, `TransportConfig` and `ConfigKey` (checked, typed properties that reject unknown keys),
  `SendContext`, `Precheck` and `BatchItemResult`; plus `Secret` (redacted credentials from a value, a supplier or an
  environment variable) and `InvalidConfigException`.
- `TransportRegistry`: finds providers with `ServiceLoader`, fails at startup when a provider needs a newer
  mailroom-core (`mailroom-resend 1.3.0 needs mailroom-core ≥ 1.2, found 1.1.0`) or two share an id, and builds named
  transports from `transports.<name>.type` properties, reporting every problem at once.
- `mailroom-test`: `InMemoryTransport`, which records and accepts every email and offers assertions such as
  `assertSentOne(to, tag)`, and `ScriptedTransport`, which succeeds, fails with a given `FailureKind` or waits, one
  scripted step per call. No test-framework dependency: assertions throw `AssertionError`.
- Routing (internal, behind the coming `Mailroom` facade): drops transports that lack a needed capability, don't
  understand an essential extension, exceed a limit or fail their precheck; then tries the rest in order, retrying
  `UNAVAILABLE` once after a jittered backoff, waiting for `RATE_LIMITED` only within `maxInlineWait`, failing over on
  `REJECTED` and `AUTHENTICATION`, and stopping on `INVALID_MESSAGE` and (by default) `UNKNOWN_OUTCOME`.
- `Mailroom`, the facade: `send`, `sendAsync`, `via(...)` and `close`, built with `Mailroom.builder()` (default sender,
  transports under their own or a chosen name, default route, retry settings, executor, clock) or
  `Mailroom.fromProperties(...)` (`default-from`, `routing.default`, `failover-on-unknown-outcome`, `retry-backoff`,
  `max-inline-wait`, `transports.<name>.*`, every problem reported at once). `sendAsync` runs on a virtual thread per
  send on Java 21 and newer and on a small bounded pool on Java 8.
- Logging through `java.util.logging` on the `io.primelogiclab.mailroom` logger: each send at `INFO` (transport,
  attempt count, message id), failures at `WARNING`, attempts and skips at `FINE`, with addresses masked and never a
  subject, body, header, secret or exception cause.
- `MailroomListener` for metrics and tracing (`onSkip`, `onAttempt`, `onSuccess`, `onFailure`), added with
  `MailroomBuilder.listener(...)` or `Mailroom.fromProperties(properties, listener)`; an exception from a listener is
  logged and ignored.
- `mailroom-tck`: `StubHttpServer`, a loopback HTTP server on the JDK's `com.sun.net.httpserver` that records each
  request (`RecordedRequest`, whose `toString` never shows header values or the body) and plays scripted
  `StubResponse`s: status, headers and body, a delay, a dropped connection, or a body cut short.
- `mailroom-http`: `HttpEngine`, found with `HttpEngines.load()` (an engine registered for `ServiceLoader`, else the
  built-in `HttpURLConnection` one), with `HttpRequest` and `HttpResponse`. Requests are https only, apart from plain
  http to loopback, and their `toString` never shows header values, the query or the body. Every status comes back as
  a response. An I/O failure throws `SendFailedException`: `UNAVAILABLE` before the request went out, `UNKNOWN_OUTCOME`
  after, including a response cut short. The engine follows the JVM proxy settings, never follows redirects and never
  re-sends a POST.
- `mailroom-http` JSON on jackson-core 2.21 (streaming only; no Jackson type in the API): `JsonWriter` writes request
  bodies field by field as UTF-8 (optional fields, string lists, base64, plain `Map`/`List` trees) and fails on
  duplicate fields, unclosed scopes, NaN and unsupported types; `JsonValue` reads responses tolerantly, so absent or
  unknown fields never throw, and compares by content. `HttpResponse.json()` throws `SendFailedException`
  `UNKNOWN_OUTCOME` for an empty or malformed success body, `tryJson()` reads error bodies when they are JSON, and
  `HttpRequest.Builder.json(byte[])` takes the writer's bytes. Parse errors give the line and column, never the input.

[Unreleased]: https://github.com/primelogiclabio/mailroom/commits/main
