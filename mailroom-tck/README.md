# mailroom-tck

The contract tests every mailroom transport runs, and a stub HTTP server to run them against. A transport module adds
`mailroom-tck` in test scope, extends the four contract classes in its own tests, and fills in a few hooks. The
contracts are JUnit 5 tests (`junit-jupiter-api` comes with this module) and use plain JUnit assertions.

```xml
<dependency>
    <groupId>io.primelogiclab.mailroom</groupId>
    <artifactId>mailroom-tck</artifactId>
    <scope>test</scope>
</dependency>
```

## The contracts

| Class | Checks | Hooks |
| --- | --- | --- |
| `MappingContract` | Every canonical email reaches the provider intact: sender, recipients, reply-to, subject, bodies, attachments, inline parts, custom headers, tags, metadata | `newTransport()`, `acceptNextSend()`, `lastSent()` |
| `FailureContract` | Each failure throws `SendFailedException` with the expected kind, status and retry-after, naming the transport and provider | `newTransport()`, `failureCases()` |
| `RedactionContract` | The credential and full addresses never show in exceptions (causes included), `toString`s or `java.util.logging` records at any level | `newTransport(Secret)`, `acceptNextSend()`, `failureCases()` |
| `PrecheckContract` | Precheck passes every canonical email the transport can carry, rejects the ones it can't take with a masked reason, and never throws | `newTransport()`, `rejectedEmails()` |

Each case runs as a JUnit dynamic test named after it. For every case the contract calls the priming hook first
(`acceptNextSend()` or `FailureCase.prime()`), then `newTransport()`, then sends; it closes the transport afterwards.
Dynamic tests share one `@BeforeEach`, so a stub server started there serves every case of one contract; keep any
per-case state in the hooks.

### Canonical emails

`TckEmails.all()` holds the emails `MappingContract` and `PrecheckContract` use: minimal text, text and HTML, HTML
only, recipients (to, cc, bcc, reply-to), Unicode, attachments (all 256 byte values, a text file), an inline image,
custom headers, tags, metadata, and one with everything. An email that needs a capability the transport doesn't
claim (`TckEmails.needs(email)`) is skipped, and parts the transport doesn't claim aren't compared.

### Mapping: decode what the provider received

`lastSent()` returns what the provider received, decoded back into an `Email`. That decoding is the transport's
statement of how it maps the model: an SMTP2GO transport parses the recorded request JSON, turning `x-mailroom-*`
custom headers back into tags and metadata; an SMTP transport parses the captured MIME message. Recipients are
compared as sets, line endings and trailing newlines in bodies don't count, content types are compared without
parameters, and extra headers are allowed.

### Failures: `FailureCase` and the HTTP helpers

A `FailureCase` is a name, a `Runnable` that makes the next send fail, and the expected kind; `.status(int)` and
`.retryAfter(Duration)` add expectations, checked only when stated.

`HttpFailureCases.standard(server)` gives the cases every HTTP transport shares, with an empty JSON body so the
transport's defaults decide: 400 `INVALID_MESSAGE`; 401 and 403 `AUTHENTICATION`; 409 `UNKNOWN_OUTCOME`; 429 with
`Retry-After: 2` `RATE_LIMITED` (2 s); 500 and 503 `UNAVAILABLE`; 504 `UNKNOWN_OUTCOME`; a connection closed after the
request `UNKNOWN_OUTCOME`. `HttpFailureCases.readTimeout(server, delay)` adds a response slower than the transport's
read timeout. Add your provider's error codes as further cases.

### Redaction: what may be left out

Configure the transport with the `Secret` the contract passes (it reveals `RedactionContract.CANARY_SECRET`); the
contract sends `RedactionContract.CANARY_EMAIL`. A masked address such as `c***@tck-canary.example.com` is fine.
`ignoredLoggers()` leaves out loggers the transport can't change, and every one left out is a leak to document. The
JDK's `HttpURLConnection` is one: it logs every request header, API keys included, at `FINE` on
`sun.net.www.protocol.http.HttpURLConnection`, so applications should keep that logger at `INFO` or above.

## Example: an HTTP transport

```java
class Smtp2GoContractsTest {

    private StubHttpServer server;

    @BeforeEach
    void start() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Smtp2GoTransport transport(Secret apiKey) {
        return new Smtp2GoTransport("smtp2go", server.baseUri(), apiKey, Duration.ofMillis(300));
    }

    private List<FailureCase> cases() {
        List<FailureCase> cases = new ArrayList<>(HttpFailureCases.standard(server));
        cases.add(HttpFailureCases.readTimeout(server, Duration.ofSeconds(1)));
        cases.add(FailureCase.of("endpoint not enabled for the key",
                () -> server.enqueue(StubResponse.status(400).json(
                        "{\"data\":{\"error_code\":\"E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED\"}}")),
                FailureKind.AUTHENTICATION).status(400));
        return cases;
    }

    @Nested
    class Mapping extends MappingContract {
        protected MailTransport newTransport() { return transport(Secret.of("k")); }
        protected void acceptNextSend() { server.enqueue(StubResponse.status(200).json(SUCCESS)); }
        protected Email lastSent() { return Smtp2GoJson.decode(server.lastRequest().bodyAsString()); }
    }

    @Nested
    class Failures extends FailureContract {
        protected MailTransport newTransport() { return transport(Secret.of("k")); }
        protected List<FailureCase> failureCases() { return cases(); }
    }

    @Nested
    class Redaction extends RedactionContract {
        protected MailTransport newTransport(Secret secret) { return transport(secret); }
        protected void acceptNextSend() { server.enqueue(StubResponse.status(200).json(SUCCESS)); }
        protected List<FailureCase> failureCases() { return cases(); }
        protected Set<String> ignoredLoggers() {
            return Collections.singleton("sun.net.www.protocol.http.HttpURLConnection");
        }
    }

    @Nested
    class Prechecks extends PrecheckContract {
        protected MailTransport newTransport() { return transport(Secret.of("k")); }
        protected Map<String, Email> rejectedEmails() {
            return Collections.singletonMap("over 50 MB", emailWithAttachmentOf(51 * 1024 * 1024));
        }
    }
}
```

## Self-check

This module's own tests run the contracts against `mailroom-test`'s `ScriptedTransport` (failures, redaction,
precheck) and `InMemoryTransport` (mapping), and check that each contract fails a transport that breaks it.
`mailroom-http`'s tests run the failure and redaction contracts against a small transport built on its engine and
`FailureMapper`.

## The stub server

`StubHttpServer` runs on the JDK's built-in `com.sun.net.httpserver` on a loopback port. It records each request
before answering and plays a script of `StubResponse`s (status, headers, body, a delay, a dropped connection, a body cut
short), then a 200 fallback. `RecordedRequest.toString()` hides header values, the query and the body.
