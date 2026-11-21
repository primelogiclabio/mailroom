# mailroom

Small Java email API with pluggable provider modules: the core library, HTTP helper, test kit and provider contract tests.

Your code sends through one small API. Each email provider (SMTP2GO, SendGrid, Resend, Microsoft 365, plain SMTP)
is a separate module with its own releases, found at runtime with `java.util.ServiceLoader`. An app can run several
providers at once, route messages between them and fail over when one is down. Adding a provider never needs a new
release of the core.

> **Status:** early development. The modules below are empty skeletons; the API arrives step by step, and there is
> no release yet. Snapshots are published from `main`.

## Modules

| Artifact | What it holds | Depends on |
| --- | --- | --- |
| `mailroom-core` | The `Email` model, the `Mailroom` sending API, the router and the provider SPI | JDK only |
| `mailroom-http` | Shared HTTP and JSON plumbing for provider modules (for provider authors, not application API) | core |
| `mailroom-test` | `InMemoryTransport` and `ScriptedTransport` for application tests | core |
| `mailroom-tck` | Contract tests every transport module runs | core |

Provider modules live in their own repos, for example
[mailroom-smtp](https://github.com/primelogiclabio/mailroom-smtp) and
[mailroom-smtp2go](https://github.com/primelogiclabio/mailroom-smtp2go).

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
