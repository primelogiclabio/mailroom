# mailroom

Java SMTP mailing library with connection reuse, STARTTLS/TLS and automatic retry.

## Pick your build

Both builds have the same API (`io.primelogiclab.mailroom`); pick the one that matches your Java version and mail namespace.

| Artifact | Java | Mail API | Fits |
|---|---|---|---|
| `mailroom-java8` | 8+ | `javax.mail` (JavaMail 1.6) | Spring Boot 2, Java EE 8, Tomcat 9 and older |
| `mailroom-java21` | 21+ | `jakarta.mail` (Eclipse Angus) | Spring Boot 3+, Jakarta EE 9+, Tomcat 10+ |

`mailroom-java21` also uses a lock instead of `synchronized`, so a shared `Mailer` is safe and efficient to call from virtual threads.

```xml
<dependency>
    <groupId>io.primelogiclab</groupId>
    <artifactId>mailroom-java21</artifactId> <!-- or mailroom-java8 -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Features

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

On JDK 21 or newer, build and install both:

```bash
mvn install
```

On JDK 8, build only the Java 8 module:

```bash
mvn -pl mailroom-java8 -am install
```

The Java 8 module is compiled with `--release 8` on newer JDKs, so any Java 9+ API call fails the build. CI tests each module on its own minimum JDK (8 and 21) on Linux and Windows.

Changes to the library usually need making in both `mailroom-java8/src` and `mailroom-java21/src`.

## License

[MIT](LICENSE)
