package io.primelogiclab.mailroom;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class MailerTest {

    private GreenMail server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private GreenMail start(ServerSetup setup) {
        server = new GreenMail(setup);
        server.start();
        return server;
    }

    private static MailConfig.Builder plainConfig(int port) {
        return MailConfig.builder("localhost")
                .port(port)
                .security(Security.NONE)
                .retryDelay(Duration.ZERO);
    }

    private static Email email(String subject) {
        return Email.builder()
                .from("sender@example.com")
                .to("alice@example.com")
                .subject(subject)
                .text("Hello " + subject)
                .build();
    }

    @Test
    void sendsPlainEmail() throws Exception {
        GreenMail gm = start(ServerSetupTest.SMTP.dynamicPort());

        Mailer.sendOnce(plainConfig(gm.getSmtp().getPort()).build(), email("one"));

        MimeMessage[] received = gm.getReceivedMessages();
        assertEquals(1, received.length);
        assertEquals("one", received[0].getSubject());
        assertEquals("Hello one", GreenMailUtil.getBody(received[0]).trim());
    }

    @Test
    void reusesOneConnectionForManyEmails() {
        GreenMail gm = start(ServerSetupTest.SMTP.dynamicPort());

        Mailer mailer = new Mailer(plainConfig(gm.getSmtp().getPort()).build());
        try (mailer) {
            for (int i = 0; i < 100; i++) {
                mailer.send(email("bulk " + i));
            }
            assertTrue(mailer.isOpen());
        }

        assertFalse(mailer.isOpen(), "try-with-resources should close the connection");
        assertEquals(1, mailer.connectionsOpened());
        assertEquals(100, gm.getReceivedMessages().length);
    }

    @Test
    void sharedMailerWorksFromVirtualThreads() throws Exception {
        GreenMail gm = start(ServerSetupTest.SMTP.dynamicPort());

        try (Mailer mailer = new Mailer(plainConfig(gm.getSmtp().getPort()).build());
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sends = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Email e = email("vt " + i);
                sends.add(pool.submit(() -> mailer.send(e)));
            }
            for (Future<?> f : sends) {
                f.get();
            }
            assertEquals(1, mailer.connectionsOpened());
        }
        assertEquals(50, gm.getReceivedMessages().length);
    }

    @Test
    void explicitOpenAndClose() {
        GreenMail gm = start(ServerSetupTest.SMTP.dynamicPort());
        Mailer mailer = new Mailer(plainConfig(gm.getSmtp().getPort()).build());

        assertFalse(mailer.isOpen());
        mailer.open();
        assertTrue(mailer.isOpen());
        mailer.send(email("a"));
        mailer.close();
        assertFalse(mailer.isOpen());
        mailer.close(); // idempotent

        // reusable after close
        mailer.send(email("b"));
        mailer.close();
        assertEquals(2, mailer.connectionsOpened());
        assertEquals(2, gm.getReceivedMessages().length);
    }

    @Test
    void authenticatesWithCredentials() {
        GreenMail gm = start(ServerSetupTest.SMTP.dynamicPort());
        gm.getUserManager().setAuthRequired(true);
        gm.setUser("sender@example.com", "bob", "secret");

        MailConfig config = plainConfig(gm.getSmtp().getPort()).credentials("bob", "secret").build();
        Mailer.sendOnce(config, email("auth"));
        assertEquals(1, gm.getReceivedMessages().length);

        MailConfig wrong = plainConfig(gm.getSmtp().getPort()).credentials("bob", "nope").build();
        MailException ex = assertThrows(MailException.class, () -> Mailer.sendOnce(wrong, email("x")));
        assertEquals(2, ex.getSuppressed().length, "3 attempts: 1 cause + 2 suppressed");
    }

    @Test
    void sendsOverImplicitTls() throws Exception {
        GreenMail gm = start(ServerSetupTest.SMTPS.dynamicPort());

        MailConfig config = MailConfig.builder("localhost")
                .port(gm.getSmtps().getPort())
                .security(Security.TLS)
                .sslTrust("*") // GreenMail uses a self-signed certificate
                .property("mail.smtps.ssl.checkserveridentity", "false")
                .retryDelay(Duration.ZERO)
                .build();

        Mailer.sendOnce(config, email("secure"));

        assertEquals("secure", gm.getReceivedMessages()[0].getSubject());
    }

    @Test
    void failsAfterThreeAttemptsWhenServerIsDown() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        Mailer mailer = new Mailer(plainConfig(deadPort).connectTimeout(Duration.ofSeconds(2)).build());

        MailException ex = assertThrows(MailException.class, () -> mailer.send(email("lost")));

        assertTrue(ex.getMessage().contains("after 3 attempt(s)"), ex.getMessage());
        assertEquals(2, ex.getSuppressed().length);
        assertFalse(mailer.isOpen());
    }

    @Test
    void reconnectsWhenServerDropsConnection() throws Exception {
        ServerSetup setup = ServerSetupTest.SMTP.dynamicPort();
        GreenMail gm = start(setup);
        int port = gm.getSmtp().getPort();

        try (Mailer mailer = new Mailer(plainConfig(port).build())) {
            mailer.send(email("before"));

            // Simulate the server dropping the idle connection.
            gm.stop();
            server = start(new ServerSetup(port, setup.getBindAddress(), setup.getProtocol()));

            mailer.send(email("after"));
            assertEquals(2, mailer.connectionsOpened());
        }
        assertEquals("after", server.getReceivedMessages()[0].getSubject());
    }

    @Test
    void sendsHtmlTextAndAttachment() throws Exception {
        GreenMail gm = start(ServerSetupTest.SMTP.dynamicPort());
        Email email = Email.builder()
                .from("Sender <sender@example.com>")
                .to("alice@example.com")
                .cc("carol@example.com")
                .bcc("dave@example.com")
                .subject("Report ✓")
                .text("plain")
                .html("<b>rich</b>")
                .attach("report.txt", "data".getBytes(), "text/plain")
                .build();

        Mailer.sendOnce(plainConfig(gm.getSmtp().getPort()).build(), email);

        MimeMessage[] received = gm.getReceivedMessages();
        assertEquals(3, received.length, "one copy per recipient incl. bcc");
        MimeMessage m = received[0];
        assertEquals("Report ✓", m.getSubject());
        assertTrue(m.getContentType().startsWith("multipart/mixed"));
        String raw = GreenMailUtil.getWholeMessage(m);
        assertTrue(raw.contains("multipart/alternative"));
        assertTrue(raw.contains("report.txt"));
        assertNull(m.getHeader("Bcc"));
    }

    @Test
    void invalidAddressFailsWithoutRetrying() {
        Mailer mailer = new Mailer(plainConfig(1).build());
        Email bad = Email.builder().from("sender@example.com").to("not an address").text("x").build();

        MailException ex = assertThrows(MailException.class, () -> mailer.send(bad));

        assertTrue(ex.getMessage().startsWith("Invalid email"), ex.getMessage());
        assertEquals(0, mailer.connectionsOpened());
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalStateException.class, () -> Email.builder().to("a@b.c").text("x").build());
        assertThrows(IllegalStateException.class, () -> Email.builder().from("a@b.c").text("x").build());
        assertThrows(IllegalStateException.class, () -> Email.builder().from("a@b.c").to("a@b.c").build());
        assertThrows(IllegalArgumentException.class, () -> MailConfig.builder(" "));
        assertThrows(IllegalArgumentException.class, () -> MailConfig.builder("h").maxAttempts(0));

        assertEquals(587, MailConfig.builder("h").build().port());
        assertEquals(465, MailConfig.builder("h").security(Security.TLS).build().port());
        assertEquals(25, MailConfig.builder("h").security(Security.NONE).build().port());
        assertEquals(3, MailConfig.builder("h").build().maxAttempts());
    }
}
