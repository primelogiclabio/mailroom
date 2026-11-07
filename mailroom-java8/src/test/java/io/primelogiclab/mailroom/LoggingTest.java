package io.primelogiclab.mailroom;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LoggingTest {

    private static final String PASSWORD = "s3cr3t-Pa55";
    private static final String BODY = "Confidential-body-text";

    // Held in a field: java.util.logging only keeps weak references to loggers.
    private final Logger mailroomLogger = Logger.getLogger("io.primelogiclab.mailroom");
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            records.add(r);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level originalLevel;
    private GreenMail server;

    @BeforeEach
    void setUp() {
        originalLevel = mailroomLogger.getLevel();
        capture.setLevel(Level.ALL);
        mailroomLogger.addHandler(capture);

        server = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        server.start();
        server.getUserManager().setAuthRequired(true);
        server.setUser("sender@example.com", "bob", PASSWORD);
    }

    @AfterEach
    void tearDown() {
        mailroomLogger.removeHandler(capture);
        mailroomLogger.setLevel(originalLevel);
        server.stop();
    }

    private MailConfig.Builder config() {
        return MailConfig.builder("localhost")
                .port(server.getSmtp().getPort())
                .security(Security.NONE)
                .credentials("bob", PASSWORD)
                .retryDelay(Duration.ZERO);
    }

    private static Email email() {
        return Email.builder().from("sender@example.com").to("alice@example.com").subject("Hi").text(BODY).build();
    }

    private List<String> messages(String loggerName, Level atLeast) {
        List<String> out = new ArrayList<>();
        for (LogRecord r : records) {
            if (r.getLoggerName().equals(loggerName) && r.getLevel().intValue() >= atLeast.intValue()) {
                out.add(r.getMessage());
            }
        }
        return out;
    }

    private static boolean anyContains(List<String> lines, String... parts) {
        for (String line : lines) {
            boolean all = true;
            for (String p : parts) {
                all &= line.contains(p);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private String server() {
        return "localhost:" + server.getSmtp().getPort() + " (NONE)";
    }

    @Test
    void debugLogsConnectionRepliesAndSmtpConversationAtInfo() {
        Mailer.sendOnce(config().debug(true).build(), email());

        List<String> info = messages(Mailer.class.getName(), Level.INFO);
        assertTrue(anyContains(info, "Connecting to " + server() + " as bob"), info.toString());
        assertTrue(anyContains(info, "Connected to " + server() + " as bob", "connection #1", "encrypted: false"), info.toString());
        assertTrue(anyContains(info, "Sent \"Hi\" <", "to 1 recipient(s) via " + server(), "server replied: 250"), info.toString());
        assertTrue(anyContains(info, "Closed connection to " + server() + " after 1 email(s)"), info.toString());

        List<String> smtp = messages("io.primelogiclab.mailroom.smtp", Level.INFO);
        assertTrue(anyContains(smtp, "EHLO"), smtp.toString());
        assertTrue(anyContains(smtp, "MAIL FROM:<sender@example.com>"), smtp.toString());
        assertTrue(anyContains(smtp, "Subject: Hi"), "headers are kept: " + smtp);
        assertTrue(anyContains(smtp, "(message body: ", "not logged)"), smtp.toString());
        assertFalse(anyContains(smtp, BODY), "body must not be logged: " + smtp);
        assertTrue(anyContains(smtp, "QUIT"), "commands after the message are logged again: " + smtp);
    }

    @Test
    void passwordIsNeverLogged() {
        mailroomLogger.setLevel(Level.ALL);
        Mailer.sendOnce(config().debug(true).build(), email());

        Base64.Encoder b64 = Base64.getEncoder();
        String[] secrets = {
                PASSWORD,
                b64.encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8)),                   // AUTH LOGIN
                b64.encodeToString(("\0bob\0" + PASSWORD).getBytes(StandardCharsets.UTF_8)),     // AUTH PLAIN
        };
        assertFalse(records.isEmpty());
        for (LogRecord r : records) {
            for (String secret : secrets) {
                assertFalse(r.getMessage().contains(secret), "password leaked in: " + r.getMessage());
            }
        }
    }

    @Test
    void withoutDebugDetailsAreFineAndNoConversation() {
        mailroomLogger.setLevel(Level.FINE);
        Mailer.sendOnce(config().build(), email());

        List<String> fine = messages(Mailer.class.getName(), Level.FINE);
        assertTrue(anyContains(fine, "Connected to " + server()), fine.toString());
        assertTrue(anyContains(fine, "server replied: 250"), fine.toString());
        assertTrue(messages(Mailer.class.getName(), Level.INFO).isEmpty(), "nothing above FINE on success");
        assertTrue(messages("io.primelogiclab.mailroom.smtp", Level.ALL).isEmpty(), "no SMTP conversation");
    }

    @Test
    void defaultLevelsLogNothingOnSuccess() {
        Mailer.sendOnce(config().build(), email());

        assertTrue(records.isEmpty(), records.toString());
    }

    @Test
    void failedAttemptsWarnWithServer() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        MailConfig config = MailConfig.builder("localhost").port(deadPort).security(Security.NONE)
                .maxAttempts(2).retryDelay(Duration.ZERO).build();

        assertThrows(MailException.class, () -> Mailer.sendOnce(config, email()));

        List<String> warnings = messages(Mailer.class.getName(), Level.WARNING);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(anyContains(warnings, "Attempt 1/2", "via localhost:" + deadPort + " (NONE) failed"), warnings.toString());
    }

    @Test
    void reportsTlsConnectionAsEncrypted() {
        GreenMail tls = new GreenMail(ServerSetupTest.SMTPS.dynamicPort());
        tls.start();
        try {
            MailConfig config = MailConfig.builder("localhost")
                    .port(tls.getSmtps().getPort())
                    .security(Security.TLS)
                    .sslTrust("*")
                    .property("mail.smtps.ssl.checkserveridentity", "false")
                    .debug(true)
                    .build();

            Mailer.sendOnce(config, email());

            List<String> info = messages(Mailer.class.getName(), Level.INFO);
            assertTrue(anyContains(info, "Connected to localhost:" + tls.getSmtps().getPort() + " (TLS)", "encrypted: true"),
                    info.toString());
        } finally {
            tls.stop();
        }
    }
}
