package io.primelogiclab.mailroom;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Immutable SMTP server settings. Create with {@link #builder(String)}.
 *
 * <pre>{@code
 * MailConfig config = MailConfig.builder("smtp.example.com")
 *         .security(Security.STARTTLS)
 *         .credentials("user", "secret")
 *         .build();
 * }</pre>
 *
 * <p>For servers that require OAuth 2.0, such as Microsoft 365 and Google, log in with an access token
 * instead of a password: {@link Builder#oauth2(String, Supplier)}.
 */
public final class MailConfig {

    /** Default number of send attempts before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String host;
    private final int port;
    private final Security security;
    private final String username;
    private final String password;
    private final Supplier<String> accessToken;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final String sslTrust;
    private final boolean debug;
    private final Properties extraProperties;

    private MailConfig(Builder b) {
        this.host = b.host;
        this.security = b.security;
        this.port = b.port > 0 ? b.port : defaultPort(b.security);
        this.username = b.username;
        this.password = b.password;
        this.accessToken = b.accessToken;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.maxAttempts = b.maxAttempts;
        this.retryDelay = b.retryDelay;
        this.sslTrust = b.sslTrust;
        this.debug = b.debug;
        this.extraProperties = new Properties();
        this.extraProperties.putAll(b.extraProperties);
    }

    public static Builder builder(String host) {
        return new Builder(host);
    }

    private static int defaultPort(Security security) {
        switch (security) {
            case NONE:
                return 25;
            case TLS:
                return 465;
            case STARTTLS:
            default:
                return 587;
        }
    }

    public String host() { return host; }
    public int port() { return port; }
    public Security security() { return security; }
    public String username() { return username; }
    public boolean hasCredentials() { return username != null; }
    /** Whether the login uses an OAuth 2.0 access token (XOAUTH2) instead of a password. */
    public boolean usesOAuth2() { return accessToken != null; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public int maxAttempts() { return maxAttempts; }
    public Duration retryDelay() { return retryDelay; }
    /** Whether connection details and the SMTP conversation are logged at INFO. */
    public boolean debug() { return debug; }

    String password() { return password; }

    /** Supplies the OAuth 2.0 access token; null unless {@link #usesOAuth2()}. */
    Supplier<String> accessToken() { return accessToken; }

    /** The protocol name Jakarta Mail should use for this config. */
    String protocol() {
        return security == Security.TLS ? "smtps" : "smtp";
    }

    /** Builds the Jakarta Mail session properties. */
    Properties toProperties() {
        String p = "mail." + protocol() + ".";
        Properties props = new Properties();
        props.put("mail.transport.protocol", protocol());
        props.put(p + "host", host);
        props.put(p + "port", String.valueOf(port));
        props.put(p + "auth", String.valueOf(hasCredentials()));
        if (usesOAuth2()) {
            // Offer only XOAUTH2, so the token is never sent as a password with LOGIN or PLAIN.
            props.put(p + "auth.mechanisms", "XOAUTH2");
            props.put(p + "auth.xoauth2.disable", "false");
        }
        props.put(p + "connectiontimeout", String.valueOf(connectTimeout.toMillis()));
        props.put(p + "timeout", String.valueOf(readTimeout.toMillis()));
        props.put(p + "writetimeout", String.valueOf(readTimeout.toMillis()));

        if (security == Security.STARTTLS) {
            props.put(p + "starttls.enable", "true");
            props.put(p + "starttls.required", "true");
        }
        if (security != Security.NONE) {
            props.put(p + "ssl.checkserveridentity", "true");
            props.put(p + "ssl.protocols", "TLSv1.2 TLSv1.3");
            if (sslTrust != null) {
                props.put(p + "ssl.trust", sslTrust);
            }
        }
        props.putAll(extraProperties);
        return props;
    }

    @Override
    public String toString() {
        return "MailConfig{" + security + "://" + host + ":" + port
                + (hasCredentials() ? ", user=" + username + (usesOAuth2() ? " (OAuth 2.0)" : "") : "")
                + ", maxAttempts=" + maxAttempts + (debug ? ", debug" : "") + "}";
    }

    public static final class Builder {
        private final String host;
        private int port;
        private Security security = Security.STARTTLS;
        private String username;
        private String password;
        private Supplier<String> accessToken;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration retryDelay = Duration.ofSeconds(1);
        private String sslTrust;
        private boolean debug;
        private final Properties extraProperties = new Properties();

        private Builder(String host) {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("host is required");
            }
            this.host = host;
        }

        /** Server port. Defaults to 25 / 587 / 465 depending on {@link #security(Security)}. */
        public Builder port(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            this.port = port;
            return this;
        }

        /** Connection security. Defaults to {@link Security#STARTTLS}. */
        public Builder security(Security security) {
            this.security = Objects.requireNonNull(security, "security");
            return this;
        }

        /** Enables SMTP AUTH with the given credentials. */
        public Builder credentials(String username, String password) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password");
            return this;
        }

        /**
         * Logs in with an OAuth 2.0 access token (SASL XOAUTH2) instead of a password, as Microsoft 365
         * and Google require. {@code accessToken} is called each time a connection is opened (the first
         * send, a reconnect, and each retry), so it should return a current token: cache it and renew it
         * before it expires, as OAuth client libraries do. If it throws, the attempt fails and is retried.
         * The token is never logged.
         *
         * @param username    the mailbox to log in as, for example {@code noreply@contoso.com}
         * @param accessToken returns the access token, for example {@code tokenClient::getAccessToken}
         */
        public Builder oauth2(String username, Supplier<String> accessToken) {
            this.username = Objects.requireNonNull(username, "username");
            this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = requirePositive(timeout, "connectTimeout");
            return this;
        }

        public Builder readTimeout(Duration timeout) {
            this.readTimeout = requirePositive(timeout, "readTimeout");
            return this;
        }

        /** Total send attempts per email (first try included). Defaults to 3. */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** Pause between attempts. Defaults to 1 second; {@link Duration#ZERO} retries immediately. */
        public Builder retryDelay(Duration delay) {
            Objects.requireNonNull(delay, "retryDelay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("retryDelay must not be negative");
            }
            this.retryDelay = delay;
            return this;
        }

        /**
         * Hosts whose TLS certificate is trusted without validation ("*" for all).
         * Only for test servers with self-signed certificates; never use in production.
         */
        public Builder sslTrust(String hosts) {
            this.sslTrust = hosts;
            return this;
        }

        /**
         * Logs connection details, each email sent with the server's reply, and the SMTP conversation,
         * all at INFO so they show up without changing log levels. Use while setting up or diagnosing
         * a mail server. Passwords and access tokens are never logged. Defaults to false; without it the
         * same details (but not the SMTP conversation) are logged at FINE (DEBUG).
         */
        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /** Escape hatch for any other Jakarta Mail session property. */
        public Builder property(String key, String value) {
            extraProperties.put(key, value);
            return this;
        }

        public MailConfig build() {
            if (password != null && accessToken != null) {
                throw new IllegalStateException("Use either credentials(...) or oauth2(...), not both");
            }
            return new MailConfig(this);
        }

        private static Duration requirePositive(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return d;
        }
    }
}
