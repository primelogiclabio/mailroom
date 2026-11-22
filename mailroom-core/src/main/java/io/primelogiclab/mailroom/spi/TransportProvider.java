package io.primelogiclab.mailroom.spi;

import java.util.Collections;
import java.util.Set;

/**
 * Creates transports of one provider from configuration. Each provider module registers its implementation for
 * {@link java.util.ServiceLoader}, so {@code Mailroom.fromProperties} can build {@code transports.<name>.type=<id>}
 * without knowing the provider:
 *
 * <pre>
 * # META-INF/services/io.primelogiclab.mailroom.spi.TransportProvider
 * io.primelogiclab.mailroom.smtp2go.Smtp2GoTransportProvider
 * </pre>
 *
 * <p>Implementations need a public no-argument constructor. Apps that build transports in code use the provider
 * module's own builder instead and never touch this interface.
 *
 * <p>Within a major version this interface only grows, through {@code default} methods.
 *
 * @since 0.1.0
 */
public interface TransportProvider {

    /**
     * Returns the provider id, such as {@code smtp2go}; it matches the {@code type} of a configured transport.
     *
     * @return the id, lower-case
     * @since 0.1.0
     */
    String id();

    /**
     * Creates a transport.
     *
     * @param name   the configured name, such as {@code primary}
     * @param config the transport's properties, already checked against {@link #configKeys()}
     * @return the transport
     * @throws io.primelogiclab.mailroom.InvalidConfigException if a value can't be used
     * @since 0.1.0
     */
    MailTransport create(String name, TransportConfig config);

    /**
     * Returns the keys this provider accepts. Unknown keys fail at startup, so a typo never becomes a silent 401.
     *
     * @return the keys; empty by default
     * @since 0.1.0
     */
    default Set<ConfigKey> configKeys() {
        return Collections.emptySet();
    }

    /**
     * Returns the lowest mailroom-core version this provider works with, such as {@code "0.1"} or {@code "1.2"}.
     * Core checks it when the provider is discovered and fails with a clear message instead of a
     * {@code NoSuchMethodError} in the middle of a send. Override it with the core version the provider was built
     * against.
     *
     * @return the minimum core version; {@code "0.1"} by default
     * @since 0.1.0
     */
    default String requiresCore() {
        return "0.1";
    }
}
