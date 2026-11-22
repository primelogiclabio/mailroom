package io.primelogiclab.mailroom.tck;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP server for transport tests: it records every request and answers each one as scripted, including
 * slow answers and dropped connections. It runs on the JDK's built-in {@code com.sun.net.httpserver}, so it needs no
 * extra dependency and works on Java 8.
 *
 * <pre>{@code
 * try (StubHttpServer server = StubHttpServer.start()) {
 *     server.enqueue(StubResponse.status(503),
 *                    StubResponse.status(200).json("{\"data\":{\"email_id\":\"abc\"}}"));
 *     MailTransport transport = Smtp2GoTransport.builder().baseUri(server.baseUri())...build();
 *
 *     SendResult result = transport.send(email, context);
 *
 *     assertThat(server.requestCount()).isEqualTo(2);
 *     assertThat(server.lastRequest().header("X-Smtp2go-Api-Key")).contains("test-key");
 * }
 * }</pre>
 *
 * <p>The server listens on the loopback address on a free port. Requests take the scripted responses in the order
 * they arrive; once the script runs out, every request gets the {@link #otherwise(StubResponse) fallback}, a plain
 * {@code 200} unless set. A request is recorded as soon as it has been read, before its response, so it is there even
 * when the response is delayed or dropped. Requests are handled on their own threads, so a delayed response doesn't
 * hold up the next request. The server is thread-safe.
 *
 * @since 0.1.0
 */
public final class StubHttpServer implements AutoCloseable {

    private static final AtomicInteger THREADS = new AtomicInteger();

    private final HttpServer server;
    private final ExecutorService executor;
    private final URI baseUri;
    private final Queue<StubResponse> script = new ConcurrentLinkedQueue<StubResponse>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<RecordedRequest>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile StubResponse otherwise = StubResponse.status(200);

    private StubHttpServer() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        executor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "mailroom-stub-http-" + THREADS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        server.setExecutor(executor);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                StubHttpServer.this.handle(exchange);
            }
        });
        server.start();
        baseUri = uriFor(loopback, server.getAddress().getPort());
    }

    /**
     * Starts a server on the loopback address and a free port.
     *
     * @return the running server; close it when the test ends
     * @throws UncheckedIOException if the server can't bind
     * @since 0.1.0
     */
    public static StubHttpServer start() {
        try {
            return new StubHttpServer();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the stub HTTP server", e);
        }
    }

    /**
     * Returns a loopback address on a port nothing listens on, so connecting to it is refused.
     *
     * @return such as {@code http://127.0.0.1:54322}
     * @throws UncheckedIOException if no free port can be found
     * @since 0.1.0
     */
    public static URI refusedUri() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket socket = new ServerSocket(0, 1, loopback)) {
            return uriFor(loopback, socket.getLocalPort());
        } catch (IOException e) {
            throw new UncheckedIOException("could not find a free port", e);
        }
    }

    /**
     * Returns the server's base address, for a transport's base URL.
     *
     * @return such as {@code http://127.0.0.1:54321}
     * @since 0.1.0
     */
    public URI baseUri() {
        return baseUri;
    }

    /**
     * Returns an address on this server.
     *
     * @param pathAndQuery a path starting with {@code /}, optionally with a query, such as {@code /v3/email/send}
     * @return the address
     * @throws IllegalArgumentException if the path doesn't start with {@code /}
     * @since 0.1.0
     */
    public URI uri(String pathAndQuery) {
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        if (!pathAndQuery.startsWith("/")) {
            throw new IllegalArgumentException("the path must start with /: " + pathAndQuery);
        }
        return URI.create(baseUri + pathAndQuery);
    }

    /**
     * Scripts the next responses, one per request, after any already scripted.
     *
     * @param responses the responses, in order
     * @return this server
     * @since 0.1.0
     */
    public StubHttpServer enqueue(StubResponse... responses) {
        for (StubResponse response : Objects.requireNonNull(responses, "responses")) {
            script.add(Objects.requireNonNull(response, "response"));
        }
        return this;
    }

    /**
     * Sets the response for every request after the script runs out.
     *
     * @param response the response; {@code StubResponse.status(200)} by default
     * @return this server
     * @since 0.1.0
     */
    public StubHttpServer otherwise(StubResponse response) {
        this.otherwise = Objects.requireNonNull(response, "response");
        return this;
    }

    /**
     * Returns every request received so far.
     *
     * @return the requests, oldest first; a copy
     * @since 0.1.0
     */
    public List<RecordedRequest> requests() {
        return new ArrayList<RecordedRequest>(requests);
    }

    /**
     * Returns how many requests were received.
     *
     * @return the count
     * @since 0.1.0
     */
    public int requestCount() {
        return requests.size();
    }

    /**
     * Returns the most recent request.
     *
     * @return the request
     * @throws IllegalStateException if no request was received
     * @since 0.1.0
     */
    public RecordedRequest lastRequest() {
        List<RecordedRequest> copy = requests();
        if (copy.isEmpty()) {
            throw new IllegalStateException("the stub server has received no request");
        }
        return copy.get(copy.size() - 1);
    }

    /**
     * Forgets the recorded requests, the remaining script and the fallback, for reuse between tests.
     *
     * @return this server
     * @since 0.1.0
     */
    public StubHttpServer reset() {
        script.clear();
        requests.clear();
        otherwise = StubResponse.status(200);
        return this;
    }

    /**
     * Stops the server at once, dropping any connection still open. Closing again does nothing.
     *
     * @since 0.1.0
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /**
     * Describes the server, such as {@code StubHttpServer[http://127.0.0.1:54321, 2 requests, 1 scripted left]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        int count = requests.size();
        return "StubHttpServer[" + baseUri + ", " + count + (count == 1 ? " request, " : " requests, ")
                + script.size() + " scripted left" + (closed.get() ? ", closed" : "") + "]";
    }

    private void handle(HttpExchange exchange) {
        try {
            byte[] body = readAll(exchange.getRequestBody());
            URI uri = exchange.getRequestURI();
            requests.add(new RecordedRequest(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
                    exchange.getRequestHeaders(), body));
            StubResponse response = script.poll();
            if (response == null) {
                response = otherwise;
            }
            if (!response.delayBefore().isZero()) {
                Thread.sleep(response.delayBefore().toMillis());
            }
            if (response.drops()) {
                return;     // closing before any header is sent closes the connection
            }
            for (String[] header : response.headerList()) {
                exchange.getResponseHeaders().add(header[0], header[1]);
            }
            byte[] bytes = response.bodyBytes();
            exchange.sendResponseHeaders(response.statusCode(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                OutputStream out = exchange.getResponseBody();
                int length = response.bytesBeforeDrop() < 0 ? bytes.length : response.bytesBeforeDrop();
                out.write(bytes, 0, length);
                out.flush();
            }
        } catch (IOException e) {
            // the client went away; nothing to answer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();     // the server is closing
        } finally {
            exchange.close();   // with body bytes missing, this closes the connection mid-response
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static URI uriFor(InetAddress address, int port) {
        String host = address.getHostAddress();
        return URI.create("http://" + (address instanceof Inet6Address ? "[" + host + "]" : host) + ":" + port);
    }
}
