package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * The Java 21 set's built-in engine, on {@code java.net.http.HttpClient}. Thread-safe; it keeps one client per connect
 * timeout, since {@code HttpClient} sets that per client, and the clients pool their connections.
 *
 * <p>It follows the same rules as the {@code HttpURLConnection} engine: HTTP/1.1, no redirects, the JVM's proxy
 * settings, every status returned as a response, no POST sent twice ({@code HttpClient} retries only idempotent
 * requests, and only when {@code jdk.httpclient.enableAllMethodRetry} is set). {@code HttpClient} doesn't say whether
 * the body was written, so the line between the two failure kinds is the connection: a failure to connect (refused,
 * timed out, DNS, proxy tunnel, TLS) is {@code UNAVAILABLE}, and anything after it is {@code UNKNOWN_OUTCOME}. Unlike
 * {@code HttpURLConnection}, it keeps 401 and 407 bodies and logs headers only when asked to with
 * {@code -Djdk.httpclient.HttpClient.log}.
 *
 * <p>The request's read timeout bounds the wait for the response headers ({@code HttpClient}'s request timeout), and
 * the body must then arrive within another read timeout.
 */
final class HttpClientEngine implements HttpEngine {

    /** The JVM's proxy settings at each request, as HttpURLConnection reads them, not as they were at startup. */
    private static final ProxySelector JVM_PROXIES = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            var proxies = ProxySelector.getDefault();
            return proxies == null ? List.of(Proxy.NO_PROXY) : proxies.select(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
            var proxies = ProxySelector.getDefault();
            if (proxies != null) {
                proxies.connectFailed(uri, address, e);
            }
        }
    };

    private final ConcurrentHashMap<Duration, HttpClient> clients = new ConcurrentHashMap<>();
    private volatile boolean closed;

    @Override
    public String name() {
        return "httpclient";
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            throw new IllegalStateException("this HttpEngine is closed");
        }
        var client = clients.computeIfAbsent(request.connectTimeout(), HttpClientEngine::newClient);
        var builder = java.net.http.HttpRequest.newBuilder(request.uri())
                .timeout(request.readTimeout())
                .method(request.method(), request.hasBody()
                        ? BodyPublishers.ofByteArray(request.body()) : BodyPublishers.noBody());
        request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));

        var exchange = client.sendAsync(builder.build(), BodyHandlers.ofByteArray());
        long limit = request.connectTimeout().toMillis() + 2 * request.readTimeout().toMillis();
        try {
            var response = exchange.get(limit, TimeUnit.MILLISECONDS);
            return HttpResponse.of(response.statusCode(), response.headers().map(), response.body());
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException io) {
                throw failure(request, !beforeConnected(io), io);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure(request, true, new IOException(cause));
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw failure(request, true, new IOException("the response body didn't arrive within the read timeout"));
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(request, true, new IOException("interrupted while waiting for the response"));
        }
    }

    @Override
    public void close() {
        closed = true;
        clients.values().forEach(HttpClient::close);
        clients.clear();
    }

    private static HttpClient newClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(JVM_PROXIES)
                .connectTimeout(connectTimeout)
                .build();
    }

    /** True when nothing can have reached the provider: the connection, proxy tunnel or TLS handshake failed. */
    private static boolean beforeConnected(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException || t instanceof SSLException
                    || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof java.net.UnknownHostException) {
                return true;
            }
            var message = t.getMessage();
            if (message != null && message.startsWith("Tunnel failed")) {
                return true;
            }
        }
        return false;
    }

    private static SendFailedException failure(HttpRequest request, boolean sent, IOException e) {
        var what = sent
                ? " failed after the request was sent; the provider may have accepted it: "
                : " failed before the request was sent: ";
        var detail = e.getMessage() == null ? "" : ": " + hideQuery(request, e.getMessage());
        return SendFailedException.builder(sent ? FailureKind.UNKNOWN_OUTCOME : FailureKind.UNAVAILABLE,
                request.summary() + what + e.getClass().getSimpleName() + detail).cause(e).build();
    }

    /** HttpClient messages rarely hold the address, but the query may carry a credential: never show it. */
    private static String hideQuery(HttpRequest request, String message) {
        var query = request.uri().getRawQuery();
        return query == null || query.isEmpty() ? message : message.replace(query, "(query)");
    }
}
