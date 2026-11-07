# mailroom

Java SMTP mailing library with connection reuse, STARTTLS/TLS and automatic retry.

Requires Java 17+. Built on Jakarta Mail (Eclipse Angus).

- Plain SMTP, STARTTLS, or implicit TLS (SMTPS)
- One SMTP connection reused for every email until you close it
- `AutoCloseable`: try-with-resources closes the connection
- Each email is tried up to 3 times (configurable), reconnecting between attempts, before a `MailException` is thrown

## Usage

```java
MailConfig config = MailConfig.builder("smtp.example.com")
        .security(Security.STARTTLS)          // NONE (25), STARTTLS (587, default), TLS (465)
        .credentials("user", "secret")
        .build();

try (Mailer mailer = new Mailer(config)) {
    for (String recipient : recipients) {
        mailer.send(Email.builder()
                .from("App <noreply@example.com>")
                .to(recipient)
                .subject("Hello")
                .text("Plain text body")
                .html("<p>HTML body</p>")
                .build());
    }
} // connection closed here
```

Without try-with-resources, manage it yourself:

```java
Mailer mailer = new Mailer(config);
mailer.open();       // optional: connect now to fail fast; otherwise the first send connects
mailer.send(email);
mailer.close();      // the mailer can be reused after close; the next send reconnects
```

One-off email: `Mailer.sendOnce(config, email);`

## Retry behaviour

| Setting | Default |
|---|---|
| `maxAttempts` | 3 (total tries, first included) |
| `retryDelay` | 1 second |
| `connectTimeout` / `readTimeout` | 10 s / 30 s |

After a failed attempt the connection is dropped and re-opened, so a connection the server closed while idle recovers on its own. When all attempts fail, `MailException` is thrown with the last error as its cause and earlier errors as suppressed exceptions.

Not retried: invalid emails (bad addresses, fail before any connection), and sends where the server already accepted some recipients (a retry would send them duplicates).

## Build

```bash
mvn test
mvn install
```

```xml
<dependency>
    <groupId>io.primelogiclab</groupId>
    <artifactId>mailroom</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## License

[MIT](LICENSE)
