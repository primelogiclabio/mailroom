package io.primelogiclab.mailroom.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One HTTP call for an {@link HttpEngine}: method, address, headers, body and timeouts. Immutable; checked when built.
 *
 * <pre>{@code
 * HttpRequest request = HttpRequest.post(URI.create("https://api.smtp2go.com/v3/email/send"))
 *     .header("X-Smtp2go-Api-Key", apiKey.reveal())
 *     .header("Accept", "application/json")
 *     .json(payload)
 *     .readTimeout(Duration.ofSeconds(20))
 *     .build();
 * }</pre>
 *
 * <p>The builder refuses what would be unsafe or silently changed on the wire:
 * <ul>
 *   <li>addresses other than {@code https}, except plain {@code http} to a loopback host written as {@code localhost},
 *       {@code 127.x.x.x} or {@code [::1]} (for local test servers); addresses with user info or a fragment;</li>
 *   <li>header names that aren't HTTP tokens, values with control characters, and the headers the engine owns
 *       ({@code Host}, {@code Content-Length}, {@code Connection}, {@code Transfer-Encoding} and similar), which
 *       {@code HttpURLConnection} would drop without a word; {@code Content-Type} is set with the body;</li>
 *   <li>a body on GET or DELETE;</li>
 *   <li>timeouts under 1 ms, because a zero timeout means "wait forever" to {@code HttpURLConnection}.</li>
 * </ul>
 *
 * <p>{@link #toString()} and {@link #summary()} never show header values, the query or the body, since they carry API
 * keys and addresses.
 *
 * @since 0.1.0
 */
public final class HttpRequest {

    /**
     * The connect timeout when none is set: 10 seconds.
     *
     * @since 0.1.0
     */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The read timeout when none is set: 30 seconds.
     *
     * @since 0.1.0
     */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    private static final String JSON = "application/json; charset=utf-8";

    /** Headers the engine sets itself; HttpURLConnection drops most of them silently. Lower case. */
    private static final Set<String> OWNED_HEADERS = new HashSet<String>(Arrays.asList(
            "access-control-request-headers", "access-control-request-method", "connection", "content-length",
            "content-transfer-encoding", "expect", "host", "keep-alive", "origin", "te", "trailer",
            "transfer-encoding", "upgrade", "via"));

    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private HttpRequest(Builder b) {
        this.method = b.method;
        this.uri = b.uri;
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : b.headers.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
        }
        this.headers = Collections.unmodifiableMap(copy);
        this.body = b.body;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
    }

    /**
     * Starts a POST request.
     *
     * @param uri the address: {@code https}, or {@code http} to a loopback host
     * @return a builder
     * @throws IllegalArgumentException if the address is not allowed
     * @since 0.1.0
     */
    public static Builder post(URI uri) {
        return new Builder("POST", uri);
    }

    /**
     * Starts a PUT request.
     *
     * @param uri the address: {@code https}, or {@code http} to a loopback host
     * @return a builder
     * @throws IllegalArgumentException if the address is not allowed
     * @since 0.1.0
     */
    public static Builder put(URI uri) {
        return new Builder("PUT", uri);
    }

    /**
     * Starts a GET request, which has no body.
     *
     * @param uri the address: {@code https}, or {@code http} to a loopback host
     * @return a builder
     * @throws IllegalArgumentException if the address is not allowed
     * @since 0.1.0
     */
    public static Builder get(URI uri) {
        return new Builder("GET", uri);
    }

    /**
     * Starts a DELETE request, which has no body.
     *
     * @param uri the address: {@code https}, or {@code http} to a loopback host
     * @return a builder
     * @throws IllegalArgumentException if the address is not allowed
     * @since 0.1.0
     */
    public static Builder delete(URI uri) {
        return new Builder("DELETE", uri);
    }

    /**
     * Returns the method.
     *
     * @return {@code POST}, {@code PUT}, {@code GET} or {@code DELETE}
     * @since 0.1.0
     */
    public String method() {
        return method;
    }

    /**
     * Returns the address, query included. Don't log it: the query may hold a credential.
     *
     * @return the address
     * @since 0.1.0
     */
    public URI uri() {
        return uri;
    }

    /**
     * Returns the headers in the order they were added. Names keep the spelling of their first use; a name added
     * again in another letter case joins the same entry. {@code Content-Type} is here when the request has a body.
     * Don't log the values: they carry credentials.
     *
     * @return values by header name; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Returns the first value of a header.
     *
     * @param name the header name, in any case
     * @return the value, if the request has the header
     * @since 0.1.0
     */
    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name");
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return Optional.of(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * Tells whether the request sends a body. POST and PUT always do, possibly an empty one, so that a
     * {@code Content-Length} is sent; GET and DELETE never do.
     *
     * @return true for POST and PUT
     * @since 0.1.0
     */
    public boolean hasBody() {
        return body != null;
    }

    /**
     * Returns the body's length.
     *
     * @return the length in bytes; 0 without a body
     * @since 0.1.0
     */
    public int bodyLength() {
        return body == null ? 0 : body.length;
    }

    /**
     * Returns the body.
     *
     * @return a copy of the body; empty without one
     * @since 0.1.0
     */
    public byte[] body() {
        return body == null ? new byte[0] : body.clone();
    }

    /**
     * Writes the body to a stream without copying it, for engines. Writes nothing without a body; doesn't close the
     * stream.
     *
     * @param out where to write
     * @throws IOException if writing fails
     * @since 0.1.0
     */
    public void writeBody(OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        if (body != null && body.length > 0) {
            out.write(body);
        }
    }

    /**
     * Returns the connect timeout, which covers opening the connection and the TLS handshake but not the DNS lookup.
     *
     * @return the timeout; {@link #DEFAULT_CONNECT_TIMEOUT} unless set
     * @since 0.1.0
     */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns the read timeout: the longest wait for the response or for more of it. With {@code HttpURLConnection}
     * it applies to each read, so a slow trickle can take longer in total.
     *
     * @return the timeout; {@link #DEFAULT_READ_TIMEOUT} unless set
     * @since 0.1.0
     */
    public Duration readTimeout() {
        return readTimeout;
    }

    /**
     * Returns the method and the address without its query, with any path segment holding an email address shown as
     * {@code ***}, so it is safe for logs and exception messages, such as
     * {@code POST https://api.smtp2go.com/v3/email/send}.
     *
     * @return the summary
     * @since 0.1.0
     */
    public String summary() {
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        String host = uri.getHost();
        return method + " " + uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + maskAddresses(path);
    }

    /**
     * Describes the request without header values, the query or the body, such as
     * {@code POST https://api.smtp2go.com/v3/email/send (512 bytes, headers: X-Smtp2go-Api-Key, Content-Type)}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(summary());
        if (uri.getRawQuery() != null) {
            s.append("?(query)");
        }
        s.append(" (");
        if (body != null) {
            s.append(body.length).append(body.length == 1 ? " byte" : " bytes");
        } else {
            s.append("no body");
        }
        if (!headers.isEmpty()) {
            s.append(", headers: ");
            boolean first = true;
            for (String name : headers.keySet()) {
                s.append(first ? "" : ", ").append(name);
                first = false;
            }
        }
        return s.append(')').toString();
    }

    /**
     * Replaces every path segment holding an {@code @}, such as Graph's {@code /users/sender@example.com/sendMail}, with
     * {@code ***}. Not core's {@code p***@example.com} form: core masks exception messages again, and its address
     * pattern allows {@code /}, so it would swallow the path before the {@code @}.
     */
    static String maskAddresses(String path) {
        if (path.indexOf('@') < 0) {
            return path;
        }
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            out.append(i == 0 ? "" : "/").append(segments[i].indexOf('@') >= 0 ? "***" : segments[i]);
        }
        return out.toString();
    }

    private static URI checkUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("the address must be absolute https (or http to a loopback host), not "
                    + (scheme == null ? "a relative address" : scheme + ":"));
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("the address has no valid host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("the address must not hold user info; send credentials in a header");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("the address must not have a fragment");
        }
        if ("http".equals(scheme) && !isLoopbackLiteral(host)) {
            throw new IllegalArgumentException("plain http is only allowed to a loopback host (localhost, 127.x.x.x, "
                    + "[::1]); use https for " + host);
        }
        return uri;
    }

    /** Loopback written literally: no DNS lookup, so a name can't resolve its way past the https rule. */
    static boolean isLoopbackLiteral(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost")) {
            return true;
        }
        if (IPV4_LOOPBACK.matcher(h).matches()) {
            for (String part : h.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        if (h.startsWith("[") && h.endsWith("]") && h.indexOf(':') > 0) {
            try {
                // an IPv6 literal is parsed, never looked up
                return InetAddress.getByName(h.substring(1, h.length() - 1)).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isToken(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\t' && Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    private static Duration checkTimeout(String what, Duration timeout) {
        Objects.requireNonNull(timeout, what);
        if (timeout.isNegative() || timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(what + " must be from 1 ms to " + Integer.MAX_VALUE + " ms");
        }
        return timeout;
    }

    /**
     * Collects a request. Start one with {@link HttpRequest#post(URI)}, {@link HttpRequest#put(URI)},
     * {@link HttpRequest#get(URI)} or {@link HttpRequest#delete(URI)}.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final String method;
        private final URI uri;
        private final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        /** Canonical spelling of each header name, by lower case. */
        private final Map<String, String> names = new TreeMap<String, String>();
        private byte[] body;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;

        private Builder(String method, URI uri) {
            this.method = method;
            this.uri = checkUri(uri);
            if (method.equals("POST") || method.equals("PUT")) {
                this.body = new byte[0];
            }
        }

        /**
         * Adds a header; call again to add more values for the same name.
         *
         * @param name  the name, an HTTP token such as {@code X-Smtp2go-Api-Key}
         * @param value the value, without control characters other than tab
         * @return this builder
         * @throws IllegalArgumentException if the name isn't a token or is a header the engine sets itself (use
         *                                  {@link #body(String, byte[])} for {@code Content-Type}), or the value has
         *                                  a control character
         * @since 0.1.0
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (!isToken(name)) {
                throw new IllegalArgumentException("the header name is not an HTTP token");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (OWNED_HEADERS.contains(lower)) {
                throw new IllegalArgumentException("the engine sets the " + name + " header itself");
            }
            if (lower.equals("content-type")) {
                throw new IllegalArgumentException("set Content-Type with body(contentType, bytes) or json(text)");
            }
            if (hasControl(value)) {
                throw new IllegalArgumentException("the value of the " + name
                        + " header has a control character (CR, LF or similar)");
            }
            add(name, value);
            return this;
        }

        /**
         * Sets the body and its {@code Content-Type}, replacing any earlier body.
         *
         * @param contentType the media type, such as {@code application/octet-stream}
         * @param body        the bytes; copied
         * @return this builder
         * @throws IllegalArgumentException if the method is GET or DELETE, or the content type is empty or has a
         *                                  control character
         * @since 0.1.0
         */
        public Builder body(String contentType, byte[] body) {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(body, "body");
            if (this.body == null) {
                throw new IllegalArgumentException(method + " requests have no body");
            }
            if (contentType.trim().isEmpty() || hasControl(contentType)) {
                throw new IllegalArgumentException("the content type must be non-empty, without control characters");
            }
            String existing = names.remove("content-type");
            if (existing != null) {
                headers.remove(existing);
            }
            add("Content-Type", contentType);
            this.body = body.clone();
            return this;
        }

        /**
         * Sets a JSON body, sent as UTF-8 with {@code Content-Type: application/json; charset=utf-8}.
         *
         * @param json the JSON text
         * @return this builder
         * @throws IllegalArgumentException if the method is GET or DELETE
         * @since 0.1.0
         */
        public Builder json(String json) {
            Objects.requireNonNull(json, "json");
            return body(JSON, json.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Sets the connect timeout.
         *
         * @param timeout from 1 ms to {@code Integer.MAX_VALUE} ms
         * @return this builder
         * @throws IllegalArgumentException if the timeout is out of range
         * @since 0.1.0
         */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = checkTimeout("connectTimeout", timeout);
            return this;
        }

        /**
         * Sets the read timeout.
         *
         * @param timeout from 1 ms to {@code Integer.MAX_VALUE} ms
         * @return this builder
         * @throws IllegalArgumentException if the timeout is out of range
         * @since 0.1.0
         */
        public Builder readTimeout(Duration timeout) {
            this.readTimeout = checkTimeout("readTimeout", timeout);
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the request
         * @since 0.1.0
         */
        public HttpRequest build() {
            return new HttpRequest(this);
        }

        private void add(String name, String value) {
            String lower = name.toLowerCase(Locale.ROOT);
            String canonical = names.get(lower);
            if (canonical == null) {
                canonical = name;
                names.put(lower, canonical);
                headers.put(canonical, new ArrayList<String>());
            }
            headers.get(canonical).add(value);
        }
    }
}
