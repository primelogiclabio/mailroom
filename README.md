# mailroom

Small Java email API with pluggable provider modules: the core library, HTTP helper, test kit and provider contract tests.

Your code sends through one small API. Each email provider (SMTP2GO, SendGrid, Resend, Microsoft 365, plain SMTP)
is a separate module with its own releases, found at runtime with `java.util.ServiceLoader`. An app can run several
providers at once, route messages between them and fail over when one is down. Adding a provider never needs a new
release of the core.

> **Status:** early development, no release yet. `mailroom-core` and `mailroom-test` work; `mailroom-http` has its
> HTTP engine (JSON and error mapping are next), `mailroom-tck` its stub server (the contract tests are next), and the
> first transports are being built in their own repos. Snapshots are published from `main`.

## Modules

| Artifact | What it holds | Depends on |
| --- | --- | --- |
| `mailroom-core` | The `Email` model, the `Mailroom` sending API, the router and the provider SPI | JDK only |
| `mailroom-http` | Shared HTTP and JSON plumbing for provider modules (for provider authors, not application API) | core |
| `mailroom-test` | `InMemoryTransport` and `ScriptedTransport` for application tests | core |
| `mailroom-tck` | Contract tests every transport module runs, and `StubHttpServer`, a local HTTP server that records requests and plays scripted responses, delays and dropped connections | core |

Provider modules live in their own repos, for example
[mailroom-smtp](https://github.com/primelogiclabio/mailroom-smtp) and
[mailroom-smtp2go](https://github.com/primelogiclabio/mailroom-smtp2go).

## Using it

Build an `Email`, then send it through a `Mailroom` that knows your transports:

```java
Mailroom mailroom = Mailroom.builder()
    .defaultFrom("PrimeLogicLab <no-reply@primelogiclab.io>")
    .transport("primary", primaryTransport)     // from a provider module, such as mailroom-smtp2go
    .transport("backup", backupTransport)       // such as mailroom-smtp
    .build();

Email email = Email.builder()
    .to("player@example.com")
    .subject("Your tee time is confirmed")
    .text(textBody)
    .html(htmlBody)
    .tag("booking")
    .build();                                   // throws InvalidEmailException if anything is wrong

SendResult result = mailroom.send(email);       // primary, then backup if primary is down
mailroom.via("backup").send(email);             // only backup, for this call
mailroom.sendAsync(email);                      // on a virtual thread on Java 21+, a small pool on Java 8
```

Or configure it with properties; each provider module is found on the classpath by its `type`:

```properties
default-from=PrimeLogicLab <no-reply@primelogiclab.io>
transports.primary.type=smtp2go
transports.primary.api-key=...
transports.backup.type=smtp
transports.backup.host=smtp.example.com
routing.default=primary,backup
```

```java
Mailroom mailroom = Mailroom.fromProperties(properties);   // fails at startup on any unknown or missing key
```

Failures are sorted into one `FailureKind`: `UNAVAILABLE` is retried once and then fails over, `REJECTED` and
`AUTHENTICATION` fail over, `INVALID_MESSAGE` stops, and `UNKNOWN_OUTCOME` (the provider may already be delivering it)
stops unless you turn on `failoverOnUnknownOutcome`. For tests, `mailroom-test` has `InMemoryTransport` and
`ScriptedTransport`.

## Logging and metrics

mailroom logs through `java.util.logging` to one logger, `io.primelogiclab.mailroom`, so core has no logging
dependency. SLF4J and Log4j users route it with their JUL bridge (`jul-to-slf4j`, `log4j-jul`).

| Level | What |
|---|---|
| `INFO` | each sent email: transport, provider, attempt count and the provider's message id |
| `WARNING` | each failed send: its `FailureKind`, attempt count and the transports tried; a listener that threw |
| `FINE` | each attempt and what happens next (retry, next transport, stop); each skipped transport and why |

Log lines never contain a recipient (addresses are masked, such as `p***@example.com`), a subject, a body, a header,
a secret or an exception's cause.

For metrics or tracing, add a `MailroomListener`. Override only the events you need:

```java
Mailroom mailroom = Mailroom.builder()
    .transport("primary", primaryTransport)
    .listener(new MailroomListener() {
        @Override
        public void onAttempt(Email email, Attempt attempt) {
            registry.timer("mailroom.attempt", "transport", attempt.transportName(),
                    "outcome", attempt.succeeded() ? "sent" : attempt.failureKind().get().name())
                .record(attempt.duration());
        }
    })
    .build();

Mailroom fromProps = Mailroom.fromProperties(properties, listener);
```

Events are `onSkip`, `onAttempt`, `onSuccess` and `onFailure`, called in order on the sending thread, so keep
listeners quick and thread-safe. An exception from a listener is logged and never changes the send.

## Getting the snapshots

Artifacts are published to `https://repo.primelogiclab.io`, which can be read without signing in. Add:

```xml
<repositories>
    <repository>
        <id>primelogiclab-releases</id>
        <url>https://repo.primelogiclab.io/releases</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
    <repository>
        <id>primelogiclab-snapshots</id>
        <url>https://repo.primelogiclab.io/snapshots</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

Coordinates: groupId `io.primelogiclab.mailroom`, artifactIds as above, version `0.1.0-SNAPSHOT`.

## Building

Requires Maven 3.9 or newer (or the included wrapper) and JDK 21 or newer to build:

```
./mvnw verify
```

The jars are plain Java 8 bytecode. To run the tests on another JDK, list it in `~/.m2/toolchains.xml` (see
[mailroom-parent](https://github.com/primelogiclabio/mailroom-parent#running-the-tests-on-another-jdk)) and pass its
version:

```
./mvnw verify -Dtest.jdk=8
```

CI runs the tests on JDK 8, 21 and 25.

## Compatibility

| mailroom | Runs on | Built with |
| --- | --- | --- |
| 0.1.x | Java 8, 21, 25 | Maven 3.9+, JDK 21+, [mailroom-parent](https://github.com/primelogiclabio/mailroom-parent) 0.1.0 |

## License

[Apache License 2.0](LICENSE)
