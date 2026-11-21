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

[Unreleased]: https://github.com/primelogiclabio/mailroom/commits/main
