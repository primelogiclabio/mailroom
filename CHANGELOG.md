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

[Unreleased]: https://github.com/primelogiclabio/mailroom/commits/main
