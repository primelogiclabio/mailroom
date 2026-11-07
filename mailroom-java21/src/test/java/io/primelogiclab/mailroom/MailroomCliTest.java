package io.primelogiclab.mailroom;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MailroomCliTest {

    private GreenMail server;
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void startServer() {
        server = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private int run(Map<String, String> env, String... args) throws Exception {
        PrintStream out = new PrintStream(outBytes, true, "UTF-8");
        PrintStream err = new PrintStream(errBytes, true, "UTF-8");
        return MailroomCli.run(args, out, err, env::get);
    }

    private int run(String... args) throws Exception {
        return run(Collections.<String, String>emptyMap(), args);
    }

    private String out() throws Exception {
        return outBytes.toString("UTF-8");
    }

    private String err() throws Exception {
        return errBytes.toString("UTF-8");
    }

    private String port() {
        return String.valueOf(server.getSmtp().getPort());
    }

    @Test
    void printsHelp() throws Exception {
        assertEquals(0, run("--help"));
        assertTrue(out().contains("Usage:"), out());
    }

    @Test
    void sendsSeveralEmailsOverOneConnection() throws Exception {
        int code = run("--host", "localhost", "--port", port(), "--security", "none",
                "--from", "sender@example.com", "--to", "alice@example.com", "--count", "3");

        assertEquals(0, code, err());
        assertTrue(out().contains("sent 3 email(s) over 1 connection(s)"), out());
        assertEquals(3, server.getReceivedMessages().length);
        assertEquals("mailroom test email (1/3)", server.getReceivedMessages()[0].getSubject());
    }

    @Test
    void acceptsEqualsSyntaxAndSeveralRecipients() throws Exception {
        int code = run("--host=localhost", "--port=" + port(), "--security=NONE",
                "--from=sender@example.com", "--to=alice@example.com, bob@example.com",
                "--subject=Hi", "--text=Body");

        assertEquals(0, code, err());
        assertEquals(2, server.getReceivedMessages().length, "one copy per recipient");
        assertEquals("Hi", server.getReceivedMessages()[0].getSubject());
    }

    @Test
    void logsInWithPasswordFromEnvironment() throws Exception {
        server.getUserManager().setAuthRequired(true);
        server.setUser("sender@example.com", "bob", "secret");

        int code = run(Collections.singletonMap(MailroomCli.PASSWORD_ENV, "secret"),
                "--host", "localhost", "--port", port(), "--security", "none", "--user", "bob",
                "--from", "sender@example.com", "--to", "alice@example.com");

        assertEquals(0, code, err());
        assertEquals(1, server.getReceivedMessages().length);
    }

    @Test
    void logsInWithOAuth2TokenFromEnvironment() throws Exception {
        try (FakeXOAuth2Server oauth = new FakeXOAuth2Server("noreply@example.com", "token-123")) {
            int code = run(Collections.singletonMap(MailroomCli.TOKEN_ENV, "token-123"),
                    "--host", "localhost", "--port", String.valueOf(oauth.port()), "--security", "none",
                    "--user", "noreply@example.com", "--oauth2",
                    "--from", "noreply@example.com", "--to", "alice@example.com");

            assertEquals(0, code, err());
            assertTrue(out().contains("as noreply@example.com with OAuth 2.0"), out());
            assertEquals(1, oauth.messages.size());
            assertEquals(Collections.singletonList("token-123"), oauth.tokensSeen);
        }
    }

    @Test
    void reportsFailureWhenServerIsDown() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }

        int code = run("--host", "localhost", "--port", String.valueOf(deadPort), "--security", "none",
                "--from", "sender@example.com", "--to", "alice@example.com", "--attempts", "2");

        assertEquals(1, code);
        assertTrue(err().contains("FAILED after 0 of 1 sent"), err());
        assertTrue(err().contains("after 2 attempt(s)"), err());
        assertTrue(err().contains("earlier attempt:"), err());
    }

    @Test
    void rejectsBadArguments() throws Exception {
        assertEquals(2, run("--host", "localhost", "--from", "a@example.com"));
        assertTrue(err().contains("--to is required"), err());

        assertEquals(2, run("--host", "h", "--from", "a@b.c", "--to", "a@b.c", "--security", "ssl"));
        assertTrue(err().contains("--security must be none, starttls or tls"), err());

        assertEquals(2, run("--host", "h", "--from", "a@b.c", "--to", "a@b.c", "--port", "abc"));
        assertEquals(2, run("--bogus"));
        assertEquals(2, run("--host"));

        assertEquals(2, run("--host", "h", "--from", "a@b.c", "--to", "a@b.c", "--oauth2"));
        assertTrue(err().contains("--oauth2 needs --user"), err());
    }
}
