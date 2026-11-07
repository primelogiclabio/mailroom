package io.primelogiclab.mailroom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2Test {

    private static final String USER = "noreply@example.com";

    private FakeXOAuth2Server server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private MailConfig.Builder config(Supplier<String> tokens) {
        return MailConfig.builder("localhost")
                .port(server.port())
                .security(Security.NONE)
                .oauth2(USER, tokens)
                .retryDelay(Duration.ZERO);
    }

    private static Email email(String subject) {
        return Email.builder().from(USER).to("alice@example.com").subject(subject).text("Hello").build();
    }

    @Test
    void sendsWithAccessTokenOverOneConnection() throws Exception {
        server = new FakeXOAuth2Server(USER, "token-1");
        AtomicInteger calls = new AtomicInteger();

        try (Mailer mailer = new Mailer(config(() -> {
            calls.incrementAndGet();
            return "token-1";
        }).build())) {
            for (int i = 1; i <= 3; i++) {
                mailer.send(email("Hi " + i));
            }
            assertEquals(1, mailer.connectionsOpened());
        }

        assertEquals(3, server.messages.size());
        assertEquals(Collections.singletonList("token-1"), server.tokensSeen);
        assertEquals(1, calls.get(), "the token is fetched once per connection");
    }

    @Test
    void fetchesTokenAgainWhenReconnecting() throws Exception {
        server = new FakeXOAuth2Server(USER, "token-1", "token-2");
        AtomicInteger calls = new AtomicInteger();

        try (Mailer mailer = new Mailer(config(() -> "token-" + calls.incrementAndGet()).build())) {
            mailer.send(email("first"));
            server.dropConnections();
            mailer.send(email("second"));
            assertEquals(2, mailer.connectionsOpened());
        }

        assertEquals(Arrays.asList("token-1", "token-2"), server.tokensSeen);
        assertEquals(2, server.messages.size());
    }

    @Test
    void retriesWithNewTokenWhenServerRejectsOne() throws Exception {
        server = new FakeXOAuth2Server(USER, "fresh");
        AtomicInteger calls = new AtomicInteger();

        Mailer.sendOnce(config(() -> calls.incrementAndGet() == 1 ? "expired" : "fresh").build(), email("Hi"));

        assertEquals(Arrays.asList("expired", "fresh"), server.tokensSeen);
        assertEquals(1, server.messages.size());
    }

    @Test
    void failsAfterAllAttemptsWhenTokenIsRejected() throws Exception {
        server = new FakeXOAuth2Server(USER, "valid");

        MailException e = assertThrows(MailException.class,
                () -> Mailer.sendOnce(config(() -> "revoked").maxAttempts(2).build(), email("Hi")));

        assertEquals("AuthenticationFailedException", e.getCause().getClass().getSimpleName());
        assertEquals(Arrays.asList("revoked", "revoked"), server.tokensSeen);
        assertTrue(server.messages.isEmpty());
    }

    @Test
    void retriesWhenTokenSupplierFails() throws Exception {
        server = new FakeXOAuth2Server(USER, "token");
        AtomicInteger calls = new AtomicInteger();

        Mailer.sendOnce(config(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("identity provider unavailable");
            }
            return "token";
        }).build(), email("Hi"));

        assertEquals(2, calls.get());
        assertEquals(1, server.messages.size());
    }

    @Test
    void reportsTokenSupplierFailure() throws Exception {
        server = new FakeXOAuth2Server(USER, "token");
        Supplier<String> broken = () -> {
            throw new IllegalStateException("identity provider unavailable");
        };

        MailException e = assertThrows(MailException.class,
                () -> Mailer.sendOnce(config(broken).maxAttempts(2).build(), email("Hi")));

        assertTrue(e.getMessage().contains("Could not get an OAuth 2.0 access token: identity provider unavailable"),
                e.getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertTrue(server.tokensSeen.isEmpty(), "no login attempted without a token");
    }

    @Test
    void emptyTokenIsAnError() throws Exception {
        server = new FakeXOAuth2Server(USER, "token");

        MailException e = assertThrows(MailException.class,
                () -> Mailer.sendOnce(config(() -> " ").maxAttempts(1).build(), email("Hi")));

        assertTrue(e.getMessage().contains("returned no token"), e.getMessage());
    }

    @Test
    void tokenIsNeverLogged() throws Exception {
        String token = "eyJ0eXAiOiJKV1QifQ.secret-token-value.signature";
        server = new FakeXOAuth2Server(USER, token);
        Logger logger = Logger.getLogger("io.primelogiclab.mailroom");
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Level originalLevel = logger.getLevel();
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        try {
            Mailer.sendOnce(config(() -> token).debug(true).build(), email("Hi"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(originalLevel);
        }

        String xoauth2 = Base64.getEncoder().encodeToString(
                ("user=" + USER + "\u0001auth=Bearer " + token + "\u0001\u0001").getBytes(StandardCharsets.UTF_8));
        assertTrue(logged.stream().anyMatch(m -> m.contains("as " + USER + " with OAuth 2.0")), logged.toString());
        for (String m : logged) {
            assertFalse(m.contains("secret-token-value"), "token leaked in: " + m);
            assertFalse(m.contains(xoauth2), "token leaked in: " + m);
        }
    }

    @Test
    void configOffersOnlyXOAuth2AndHidesToken() {
        MailConfig c = MailConfig.builder("smtp.office365.com").oauth2(USER, () -> "secret-token").build();

        assertTrue(c.usesOAuth2());
        assertTrue(c.hasCredentials());
        Properties props = c.toProperties();
        assertEquals("true", props.getProperty("mail.smtp.auth"));
        assertEquals("XOAUTH2", props.getProperty("mail.smtp.auth.mechanisms"));
        assertTrue(c.toString().contains("user=" + USER + " (OAuth 2.0)"), c.toString());
        assertFalse(c.toString().contains("secret-token"), c.toString());

        MailConfig tls = MailConfig.builder("smtp.gmail.com").security(Security.TLS).oauth2(USER, () -> "t").build();
        assertEquals("XOAUTH2", tls.toProperties().getProperty("mail.smtps.auth.mechanisms"));

        MailConfig password = MailConfig.builder("smtp.example.com").credentials("u", "p").build();
        assertFalse(password.usesOAuth2());
        assertNull(password.toProperties().getProperty("mail.smtp.auth.mechanisms"));
    }

    @Test
    void cannotCombinePasswordAndOAuth2() {
        MailConfig.Builder both = MailConfig.builder("smtp.example.com")
                .credentials("u", "p")
                .oauth2("u", () -> "t");
        assertThrows(IllegalStateException.class, both::build);
        assertThrows(NullPointerException.class, () -> MailConfig.builder("h").oauth2("u", null));
        assertThrows(NullPointerException.class, () -> MailConfig.builder("h").oauth2(null, () -> "t"));
    }
}
