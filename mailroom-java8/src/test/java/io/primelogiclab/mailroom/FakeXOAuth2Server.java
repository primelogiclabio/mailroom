package io.primelogiclab.mailroom;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Minimal SMTP server for tests that accepts only AUTH XOAUTH2, so the exact user and token sent on each
 * connection can be checked. Behaves the same for both builds and any mail library version.
 */
final class FakeXOAuth2Server implements Closeable {

    private static final String AUTH_XOAUTH2 = "AUTH XOAUTH2";

    private final ServerSocket server;
    private final String user;
    private final Set<String> validTokens = new CopyOnWriteArraySet<>();
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    /** Every token presented, in order. */
    final List<String> tokensSeen = new CopyOnWriteArrayList<>();
    /** The DATA of each accepted message. */
    final List<String> messages = new CopyOnWriteArrayList<>();

    FakeXOAuth2Server(String user, String... validTokens) throws IOException {
        this.user = user;
        Collections.addAll(this.validTokens, validTokens);
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "fake-xoauth2-smtp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Closes every open client connection, as a server dropping idle connections would. */
    void dropConnections() throws IOException {
        for (Socket s : clients) {
            s.close();
        }
        clients.clear();
    }

    @Override
    public void close() throws IOException {
        server.close();
        dropConnections();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                clients.add(s);
                Thread t = new Thread(() -> serve(s), "fake-xoauth2-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // server closed
            }
        }
    }

    private void serve(Socket s) {
        try (Socket socket = s;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            reply(out, "220 localhost fake ESMTP");
            boolean authenticated = false;
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    reply(out, "250-localhost\r\n250-AUTH XOAUTH2\r\n250 8BITMIME");
                } else if (command.startsWith("HELO")) {
                    reply(out, "250 localhost");
                } else if (command.startsWith(AUTH_XOAUTH2)) {
                    String response = line.substring(AUTH_XOAUTH2.length()).trim();
                    if (response.isEmpty()) {
                        reply(out, "334 ");
                        response = in.readLine();
                    }
                    authenticated = checkXOAuth2(response);
                    reply(out, authenticated ? "235 2.7.0 Authentication successful"
                            : "535 5.7.3 Authentication unsuccessful");
                } else if (command.startsWith("AUTH")) {
                    reply(out, "504 5.7.4 Unrecognized authentication type");
                } else if (command.startsWith("MAIL FROM")) {
                    reply(out, authenticated ? "250 2.1.0 Sender OK" : "530 5.7.57 Client not authenticated");
                } else if (command.startsWith("RCPT TO")) {
                    reply(out, "250 2.1.5 Recipient OK");
                } else if (command.equals("DATA")) {
                    reply(out, "354 Start mail input; end with <CRLF>.<CRLF>");
                    StringBuilder data = new StringBuilder();
                    String d;
                    while ((d = in.readLine()) != null && !d.equals(".")) {
                        data.append(d).append('\n');
                    }
                    messages.add(data.toString());
                    reply(out, "250 2.0.0 OK queued as FAKE" + messages.size());
                } else if (command.equals("NOOP") || command.equals("RSET")) {
                    reply(out, "250 2.0.0 OK");
                } else if (command.equals("QUIT")) {
                    reply(out, "221 2.0.0 Bye");
                    return;
                } else {
                    reply(out, "500 5.5.1 Unknown command");
                }
            }
        } catch (IOException e) {
            // client went away, or the connection was dropped on purpose
        } finally {
            clients.remove(s);
        }
    }

    /** Decodes {@code base64("user=" + user + "\1auth=Bearer " + token + "\1\1")} and checks it. */
    private boolean checkXOAuth2(String base64) {
        if (base64 == null) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String u = null;
        String token = null;
        for (String part : decoded.split("\u0001")) {
            if (part.startsWith("user=")) {
                u = part.substring("user=".length());
            } else if (part.startsWith("auth=Bearer ")) {
                token = part.substring("auth=Bearer ".length());
            }
        }
        tokensSeen.add(String.valueOf(token));
        return user.equals(u) && token != null && validTokens.contains(token);
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
