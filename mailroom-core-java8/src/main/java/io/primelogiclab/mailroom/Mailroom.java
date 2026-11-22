package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.MailroomProperties;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Sends email through one or more configured transports, failing over between them.
 *
 * <pre>{@code
 * Mailroom mailroom = Mailroom.builder()
 *     .defaultFrom("PrimeLogicLab <no-reply@primelogiclab.io>")
 *     .transport("primary", Smtp2GoTransport.builder().apiKey(Secret.env("SMTP2GO_API_KEY")).build())
 *     .transport("backup", SmtpTransport.builder().host("smtp.example.com").build())
 *     .build();
 *
 * SendResult result = mailroom.send(email);         // primary, then backup if primary is down
 * mailroom.via("backup").send(email);               // this call only uses backup
 * }</pre>
 *
 * <p>For each email the router drops transports that can't carry it (a missing capability, an essential extension
 * they don't understand, a limit, their own precheck), then tries the rest in order. After a failure, its
 * {@link FailureKind} decides whether to retry, fail over or stop. A {@code Mailroom} is thread-safe; build one and
 * share it. Close it when the application stops.
 *
 * @since 0.1.0
 */
public interface Mailroom extends AutoCloseable {

    /**
     * Sends an email and blocks until a provider has taken it. An email without a sender gets the configured
     * default sender.
     *
     * @param email the email
     * @return which transport took it, the provider's message id and every attempt
     * @throws SendFailedException          if the transports tried all failed, or a failure stopped the route
     * @throws NoEligibleTransportException if no transport can carry the email
     * @throws InvalidEmailException        if the email has no sender and no default sender is configured
     * @throws IllegalStateException        if this mailroom is closed
     * @since 0.1.0
     */
    SendResult send(Email email);

    /**
     * Sends an email without blocking the caller: {@link #send} runs on the mailroom's executor (a virtual thread per
     * send on Java 21 and newer, a small pool on Java 8, or the executor given to the builder).
     *
     * @param email the email
     * @return a future completed with the result, or exceptionally with the exception {@link #send} would throw
     * @throws IllegalStateException if this mailroom is closed
     * @since 0.1.0
     */
    CompletableFuture<SendResult> sendAsync(Email email);

    /**
     * Returns a view of this mailroom whose sends use only these transports, in this order.
     *
     * @param transportNames the configured transport names, such as {@code "internal"}
     * @return the pinned view; closing it does nothing
     * @throws IllegalArgumentException if a name is unknown or repeated, or no name is given
     * @since 0.1.0
     */
    Mailroom via(String... transportNames);

    /**
     * Stops accepting sends, waits briefly for asynchronous sends in progress, shuts down the executor mailroom
     * created (never one you supplied) and closes every transport. Closing again does nothing.
     *
     * @since 0.1.0
     */
    @Override
    void close();

    /**
     * Starts building a mailroom in code.
     *
     * @return a builder
     * @since 0.1.0
     */
    static MailroomBuilder builder() {
        return new MailroomBuilder();
    }

    /**
     * Builds a mailroom from properties, finding the transport providers on the classpath:
     *
     * <pre>
     * default-from=PrimeLogicLab &lt;no-reply@primelogiclab.io&gt;
     * transports.primary.type=smtp2go
     * transports.primary.api-key=...
     * transports.backup.type=smtp
     * transports.backup.host=smtp.example.com
     * routing.default=primary,backup
     * failover-on-unknown-outcome=false
     * retry-backoff=250ms
     * max-inline-wait=0s
     * </pre>
     *
     * @param properties the properties
     * @return the mailroom
     * @throws InvalidConfigException listing every problem: unknown or malformed keys, no transports, or a
     *                                transport's own configuration errors
     * @throws MailroomException      if a provider on the classpath can't be loaded or needs a newer mailroom-core
     * @since 0.1.0
     */
    static Mailroom fromProperties(Map<String, String> properties) {
        return fromProperties(properties, TransportRegistry.discover());
    }

    /**
     * Builds a mailroom from properties with providers you choose.
     *
     * @param properties the properties, as for {@link #fromProperties(Map)}
     * @param registry   the providers to use
     * @return the mailroom
     * @throws InvalidConfigException listing every problem
     * @since 0.1.0
     */
    static Mailroom fromProperties(Map<String, String> properties, TransportRegistry registry) {
        return MailroomProperties.create(properties, registry, null);
    }

    /**
     * Builds a mailroom from properties, finding the transport providers on the classpath, with a listener for
     * metrics or tracing.
     *
     * @param properties the properties, as for {@link #fromProperties(Map)}
     * @param listener   the listener
     * @return the mailroom
     * @throws InvalidConfigException listing every problem
     * @throws MailroomException      if a provider on the classpath can't be loaded or needs a newer mailroom-core
     * @since 0.1.0
     */
    static Mailroom fromProperties(Map<String, String> properties, MailroomListener listener) {
        return fromProperties(properties, TransportRegistry.discover(), listener);
    }

    /**
     * Builds a mailroom from properties with providers you choose and a listener for metrics or tracing.
     *
     * @param properties the properties, as for {@link #fromProperties(Map)}
     * @param registry   the providers to use
     * @param listener   the listener
     * @return the mailroom
     * @throws InvalidConfigException listing every problem
     * @since 0.1.0
     */
    static Mailroom fromProperties(Map<String, String> properties, TransportRegistry registry,
            MailroomListener listener) {
        return MailroomProperties.create(properties, registry, Objects.requireNonNull(listener, "listener"));
    }
}
