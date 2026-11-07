package io.primelogiclab.mailroom;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

/**
 * Immutable SMTP server settings. Create with {@link #builder(String)}.
 *
 * <pre>{@code
 * MailConfig config = MailConfig.builder("smtp.example.com")
 *         .security(Security.STARTTLS)
 *         .credentials("user", "secret")
 *         .build();
 * }</pre>
 */
public final class MailConfig {

    /** Default number of send attempts before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String host;
    private final int port;
    private final Security security;
    private final String username;
    private final String password;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final String sslTrust;
    private final Properties extraProperties;

    private MailConfig(Builder b) {
        this.host = b.host;
        this.security = b.security;
        this.port = b.port > 0 ? b.port : defaultPort(b.security);
        this.username = b.username;
        this.password = b.password;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.maxAttempts = b.maxAttempts;
        this.retryDelay = b.retryDelay;
        this.sslTrust = b.sslTrust;
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
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public int maxAttempts() { return maxAttempts; }
    public Duration retryDelay() { return retryDelay; }

    String password() { return password; }

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
                + (hasCredentials() ? ", user=" + username : "")
                + ", maxAttempts=" + maxAttempts + "}";
    }

    public static final class Builder {
        private final String host;
        private int port;
        private Security security = Security.STARTTLS;
        private String username;
        private String password;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration retryDelay = Duration.ofSeconds(1);
        private String sslTrust;
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

        /** Escape hatch for any other Jakarta Mail session property. */
        public Builder property(String key, String value) {
            extraProperties.put(key, value);
            return this;
        }

        public MailConfig build() {
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
