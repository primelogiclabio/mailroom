# mailroom

Java SMTP mailing library with connection reuse, STARTTLS/TLS and automatic retry.

## Pick your build

Both builds have the same API (`io.primelogiclab.mailroom`); pick the one that matches your Java version and mail namespace.

| Artifact | Java | Mail API | Fits |
|---|---|---|---|
| `mailroom-java8` | 8+ | `javax.mail` (JavaMail 1.6) | Spring Boot 2, Java EE 8, Tomcat 9 and older |
| `mailroom-java21` | 21+ | `jakarta.mail` (Eclipse Angus) | Spring Boot 3+, Jakarta EE 9+, Tomcat 10+ |

`mailroom-java21` also uses a lock instead of `synchronized`, so a shared `Mailer` is safe and efficient to call from virtual threads.

## Requirements

- **To use:** Java 8+ for `mailroom-java8`, Java 21+ for `mailroom-java21`.
- **To build:** Maven 3.9+, and JDK 21+ to build both modules (JDK 8 can build `mailroom-java8` only).
- **To run the tests:** nothing else. They start an embedded SMTP server (GreenMail) on a free local port.

## Dependencies

The mailroom jar contains only mailroom's own classes. It needs the mail library jars below at runtime. Maven and Gradle download them automatically; if you copy the mailroom jar by hand, put these next to it on the classpath.

| Build | Jars needed at runtime |
|---|---|
| `mailroom-java8` | `com.sun.mail:javax.mail:1.6.2`<br>`javax.activation:activation:1.1` (built into Java 8; needed on Java 11+) |
| `mailroom-java21` | `org.eclipse.angus:angus-mail:2.0.5`<br>`jakarta.mail:jakarta.mail-api:2.1.5`<br>`org.eclipse.angus:angus-activation:2.0.3`<br>`jakarta.activation:jakarta.activation-api:2.1.4` |

To collect them into `target/lib` (for example, to ship with an app that doesn't use Maven):

```bash
mvn -pl mailroom-java21 dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
```

Use `-pl mailroom-java8` for the Java 8 build.

### Adding mailroom to a project

mailroom isn't published to a public repository yet. Install it into your local Maven repository first:

```bash
mvn install
```

Maven:

```xml
<dependency>
    <groupId>io.primelogiclab</groupId>
    <artifactId>mailroom-java21</artifactId> <!-- or mailroom-java8 -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Gradle:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation 'io.primelogiclab:mailroom-java21:0.1.0-SNAPSHOT' // or mailroom-java8
}
```

If your app already brings in the mail library (for example `spring-boot-starter-mail`), Maven keeps a single version of it. To always use your app's version, exclude mailroom's copy:

```xml
<dependency>
    <groupId>io.primelogiclab</groupId>
    <artifactId>mailroom-java21</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <exclusions>
        <exclusion>
            <groupId>org.eclipse.angus</groupId> <!-- com.sun.mail for mailroom-java8 -->
            <artifactId>*</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

## Features

- Plain SMTP, STARTTLS, or implicit TLS (SMTPS)
- One SMTP connection reused for every email until you close it
- `AutoCloseable`: try-with-resources closes the connection
- Each email is tried up to 3 times (configurable), reconnecting between attempts, before a `MailException` is thrown
- Password login, or OAuth 2.0 (XOAUTH2) with an access token you supply, for Microsoft 365 and Google

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

## OAuth 2.0 (Microsoft 365, Google)

Microsoft 365 is turning off password logins for SMTP, and Google restricts them. Both accept an OAuth 2.0 access token instead. mailroom doesn't fetch tokens itself; give it a function that returns one, for example from your OAuth client library:

```java
MailConfig config = MailConfig.builder("smtp.office365.com")      // port 587 with STARTTLS, the default
        .oauth2("noreply@contoso.com", tokenClient::getAccessToken)
        .build();
```

mailroom calls the function each time it opens a connection: on the first send, after the server closes an idle connection, and on each retry. So it should return a current token, cached and renewed before it expires, which OAuth client libraries do for you. If the server rejects the token or the function throws, the attempt is retried with a new call. Tokens are never logged.

| Provider | Server | Token |
|---|---|---|
| Microsoft 365, as an app (no user sign-in) | `smtp.office365.com`, port 587 | Client credentials grant, scope `https://outlook.office365.com/.default`. The app needs the `SMTP.SendAsApp` permission, and an Exchange admin must register its service principal and grant it each mailbox it sends from |
| Microsoft 365, as a signed-in user | `smtp.office365.com`, port 587 | Scope `https://outlook.office.com/SMTP.Send`, plus `offline_access` for a refresh token |
| Google | `smtp.gmail.com`, port 587 (or 465 with `Security.TLS`) | Scope `https://mail.google.com/` |

For Microsoft 365, SMTP AUTH must also be enabled for the sending mailbox. Microsoft's setup steps: [Authenticate an IMAP, POP or SMTP connection using OAuth](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth).

## Retry behaviour

| Setting | Default |
|---|---|
| `maxAttempts` | 3 (total tries, first included) |
| `retryDelay` | 1 second |
| `connectTimeout` / `readTimeout` | 10 s / 30 s |

After a failed attempt the connection is dropped and re-opened, so a connection the server closed while idle recovers on its own. When all attempts fail, `MailException` is thrown with the last error as its cause and earlier errors as suppressed exceptions.

Not retried: invalid emails (bad addresses, fail before any connection), and sends where the server already accepted some recipients (a retry would send them duplicates).

## Logging and debugging

mailroom logs through `java.util.logging`, so it adds no dependency. Spring Boot, Tomcat and SLF4J's `jul-to-slf4j` bridge send it to your normal logs.

| What | Logger | Level |
|---|---|---|
| Connecting and connected (server, port, security, user, whether the connection is encrypted), each email sent (Message-ID, recipient count, the server's reply), connection closed | `io.primelogiclab.mailroom.Mailer` | DEBUG (`FINE`) |
| A failed attempt, before retrying | `io.primelogiclab.mailroom.Mailer` | WARNING |
| The SMTP conversation | `io.primelogiclab.mailroom.smtp` | INFO, only in debug mode |

The server's reply usually carries its queue ID (for example `250 2.0.0 OK queued as 4Xy2`), which the mail server's own logs can be searched for.

```text
Connecting to smtp.example.com:587 (STARTTLS) as me@example.com
Connected to smtp.example.com:587 (STARTTLS) as me@example.com in 212 ms (connection #1, encrypted: true)
Sent "Invoice 1042" <1993.0.17@app01> to 1 recipient(s) via smtp.example.com:587 (STARTTLS) in 95 ms; server replied: 250 2.0.0 OK queued as 4Xy2
Closed connection to smtp.example.com:587 (STARTTLS) after 1 email(s)
```

### Turn on DEBUG without changing code

Spring Boot, in `application.properties`:

```properties
logging.level.io.primelogiclab.mailroom=DEBUG
```

Logback (with `jul-to-slf4j` installed; outside Spring Boot, also add `<contextListener class="ch.qos.logback.classic.jul.LevelChangePropagator"/>` so the level reaches `java.util.logging`):

```xml
<logger name="io.primelogiclab.mailroom" level="DEBUG"/>
```

Plain `java.util.logging` or Tomcat, in `logging.properties`:

```properties
io.primelogiclab.mailroom.level = FINE
java.util.logging.ConsoleHandler.level = FINE
```

### Debug mode

For setting up or diagnosing a mail server, turn on debug mode:

```java
MailConfig config = MailConfig.builder("smtp.example.com")
        .credentials("user", "secret")
        .debug(true)
        .build();
```

It logs the details above at INFO, so they show up without changing log levels, and adds the SMTP conversation: the server's greeting, EHLO and the extensions it offers, STARTTLS, login result, `MAIL FROM` / `RCPT TO`, the message headers, and every server reply. A good pattern is to read `debug` from your app's own configuration so it can be switched on without a rebuild.

What is never logged:

- **Passwords and access tokens.** The login exchange is replaced by a line such as `AUTH LOGIN command trace suppressed`.
- **Message bodies and attachments.** The body is replaced by `(message body: N line(s), not logged)`.

Email addresses, subjects and headers are logged, so treat debug logs as containing personal data.

## Send a test email from the command line

Both jars include `MailroomCli`, which sends a test email so you can check SMTP settings before wiring them into an app. Run it with the mailroom jar and its dependencies on the classpath:

```bash
mvn install
mvn -pl mailroom-java21 dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
export MAILROOM_PASSWORD='your-smtp-password'
java -cp "mailroom-java21/target/mailroom-java21-0.1.0-SNAPSHOT.jar:mailroom-java21/target/lib/*" \
  io.primelogiclab.mailroom.MailroomCli \
  --host smtp.example.com --user me@example.com \
  --from me@example.com --to you@example.com
```

On Windows, separate classpath entries with `;` instead of `:`, and in PowerShell set the password with `$env:MAILROOM_PASSWORD = 'your-smtp-password'`. Without `MAILROOM_PASSWORD`, the tool prompts for the password. For the Java 8 build, use `mailroom-java8` in the paths.

```text
Sending 1 email(s) to you@example.com via smtp.example.com:587 (STARTTLS) as me@example.com ...
OK: sent 1 email(s) over 1 connection(s) in 812 ms
```

| Option | Meaning |
|---|---|
| `--host`, `--from`, `--to` | Required. `--to` can repeat or be comma-separated |
| `--port` | Default 25, 587 or 465 to match `--security` |
| `--security` | `none`, `starttls` (default) or `tls` |
| `--user` | Log in as this user; password from `MAILROOM_PASSWORD` or a prompt |
| `--oauth2` | With `--user`: log in with an OAuth 2.0 access token from `MAILROOM_TOKEN` or a prompt, instead of a password |
| `--subject`, `--text` | Override the default subject and body |
| `--count <n>` | Send n emails over one connection |
| `--attempts <n>` | Tries per email (default 3) |
| `--trust-any-cert` | Accept any TLS certificate; self-signed test servers only |
| `--verbose` | Debug mode: connection details, server replies and the SMTP conversation |
| `--help` | Show all options |

Exit codes: `0` sent, `1` sending failed, `2` bad arguments.

To test an OAuth 2.0 login, put an access token in `MAILROOM_TOKEN` and add `--oauth2`. For example, for Microsoft 365 as an app, using the client credentials grant:

```bash
export MAILROOM_TOKEN=$(curl -s "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token" \
  -d grant_type=client_credentials -d client_id="$CLIENT_ID" --data-urlencode client_secret="$CLIENT_SECRET" \
  -d scope=https://outlook.office365.com/.default | jq -r .access_token)
java -cp "mailroom-java21/target/mailroom-java21-0.1.0-SNAPSHOT.jar:mailroom-java21/target/lib/*" \
  io.primelogiclab.mailroom.MailroomCli \
  --host smtp.office365.com --user noreply@contoso.com --oauth2 \
  --from noreply@contoso.com --to you@example.com --verbose
```

In PowerShell, get the token with:

```powershell
$body = @{ grant_type = 'client_credentials'; client_id = $clientId; client_secret = $clientSecret; scope = 'https://outlook.office365.com/.default' }
$env:MAILROOM_TOKEN = (Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token" -Body $body).access_token
```

Tokens expire after about an hour.

## Build and test

Build, test and install both modules (JDK 21+):

```bash
mvn install
```

Run the tests only:

```bash
mvn test
```

On JDK 8, the Java 8 module only:

```bash
mvn -pl mailroom-java8 -am test
```

A single test:

```bash
mvn -pl mailroom-java21 test -Dtest='MailerTest#reusesOneConnectionForManyEmails'
```

The Java 8 module is compiled with `--release 8` on newer JDKs, so any Java 9+ API call fails the build. CI tests each module on its own minimum JDK (8 and 21) on Linux and Windows.

Changes to the library usually need making in both `mailroom-java8/src` and `mailroom-java21/src`.

## License

[MIT](LICENSE)
