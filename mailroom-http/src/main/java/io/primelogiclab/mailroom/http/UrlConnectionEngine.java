package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The built-in engine on {@code HttpURLConnection}. Stateless, so thread-safe; the JDK pools the connections.
 *
 * <p>Two {@code HttpURLConnection} habits matter here. It re-sends a POST that failed unless the body is streamed, so
 * every body goes out in fixed-length streaming mode. And it can hand back a body cut short by a closed connection
 * without an error, so the bytes read are checked against {@code Content-Length}. A third, its header logging, is
 * kept in check by {@link JdkHttpLogging}.
 */
final class UrlConnectionEngine implements HttpEngine {

    UrlConnectionEngine() {
        JdkHttpLogging.guard();
    }

    @Override
    public String name() {
        return "urlconnection";
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        JdkHttpLogging.guard();     // the JDK would log every header, credentials included, at FINE
        HttpURLConnection conn = null;
        boolean sent = false;
        boolean done = false;
        try {
            URLConnection opened = request.uri().toURL().openConnection();     // the JVM's proxy settings apply
            if (!(opened instanceof HttpURLConnection)) {
                throw new IOException("not an HTTP connection: " + opened.getClass().getName());
            }
            conn = (HttpURLConnection) opened;
            conn.setRequestMethod(request.method());
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout((int) request.connectTimeout().toMillis());
            conn.setReadTimeout((int) request.readTimeout().toMillis());
            for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                for (String value : header.getValue()) {
                    conn.addRequestProperty(header.getKey(), value);
                }
            }
            if (request.hasBody()) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(request.bodyLength());    // also stops HttpURLConnection re-sending
            }

            conn.connect();     // TCP, proxy tunnel and TLS handshake: nothing has reached the provider yet

            if (request.hasBody()) {
                OutputStream out = conn.getOutputStream();
                request.writeBody(out);
                sent = true;    // the provider may now have the whole request
                out.close();
            } else {
                sent = true;
            }

            int status = conn.getResponseCode();
            if (status < 0) {
                throw new IOException("the response had no valid HTTP status line");
            }
            Map<String, List<String>> headers = headers(conn);
            // getInputStream() on an error status throws with the full URL, query included, in its message
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body;
            if (in == null) {
                // no body, or one the JDK threw away: in streaming mode it drops a 401 or 407 body unread
                body = new byte[0];
            } else {
                body = readAll(in);
                checkLength(conn, status, body.length);
            }
            HttpResponse response = HttpResponse.of(status, headers, body);
            done = true;
            return response;
        } catch (IOException e) {
            throw failure(request, sent, e);
        } finally {
            if (!done && conn != null) {
                conn.disconnect();      // don't hand a broken connection back to the pool
            }
        }
    }

    private static SendFailedException failure(HttpRequest request, boolean sent, IOException e) {
        String what = sent
                ? " failed after the request was sent; the provider may have accepted it: "
                : " failed before the request was sent: ";
        String message = request.summary() + what + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return SendFailedException.builder(sent ? FailureKind.UNKNOWN_OUTCOME : FailureKind.UNAVAILABLE, message)
                .cause(e)
                .build();
    }

    /** In the order received; getHeaderFields() lists repeated values in reverse. Index 0 is the status line. */
    private static Map<String, List<String>> headers(HttpURLConnection conn) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (int i = 0; ; i++) {
            String value = conn.getHeaderField(i);
            if (value == null) {
                break;
            }
            String name = conn.getHeaderFieldKey(i);
            if (name == null) {
                continue;
            }
            List<String> values = headers.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                headers.put(name, values);
            }
            values.add(value);
        }
        return headers;
    }

    private static void checkLength(HttpURLConnection conn, int status, int read) throws IOException {
        if (status == 204 || status == 304 || status < 200) {
            return;
        }
        String header = conn.getHeaderField("Content-Length");
        if (header == null) {
            return;
        }
        long expected;
        try {
            expected = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (read < expected) {
            throw new IOException("the response body ended after " + read + " of " + expected + " bytes");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();     // read to the end and closed: the connection can be reused
        }
    }
}
