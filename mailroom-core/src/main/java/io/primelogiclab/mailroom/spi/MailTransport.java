package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * One configured way to send email, such as "SendGrid with the main account" or "SMTP to the office relay". Each
 * provider module implements this interface; the router calls it.
 *
 * <p>A transport is created once by its {@link TransportProvider} or its own builder and then used from many threads
 * at once, so implementations must be thread-safe. They must not hold a {@code synchronized} lock while doing I/O
 * (on Java 21 that pins a virtual thread's carrier); use {@code java.util.concurrent.locks.ReentrantLock} instead.
 *
 * <p>Within a major version this interface only grows, through {@code default} methods.
 *
 * @since 0.1.0
 */
public interface MailTransport extends AutoCloseable {

    /**
     * Returns the configured name, such as {@code primary}. Routes, rules and logs refer to transports by name.
     *
     * @return the name
     * @since 0.1.0
     */
    String name();

    /**
     * Returns the provider id, such as {@code sendgrid}; it matches {@link TransportProvider#id()}.
     *
     * @return the provider id
     * @since 0.1.0
     */
    String providerId();

    /**
     * Returns what this transport can carry. The router uses it to skip transports that can't carry an email.
     *
     * @return the capabilities; should not change after the transport is created
     * @since 0.1.0
     */
    TransportCapabilities capabilities();

    /**
     * Checks, cheaply and without any network call, whether this transport can carry the email: total size,
     * recipient counts, header rules. The default accepts every email.
     *
     * @param email the email
     * @return {@link Precheck#ok()}, or {@link Precheck#reject} with the reason
     * @since 0.1.0
     */
    default Precheck precheck(Email email) {
        return Precheck.ok();
    }

    /**
     * Sends the email and blocks until the provider has answered.
     *
     * @param email   the email, already validated
     * @param context the attempt number, deadline, executor and clock for this send
     * @return the result, with this transport's name and provider id; the router adds the attempts
     * @throws SendFailedException with the {@link io.primelogiclab.mailroom.FailureKind} the provider's answer maps to
     * @since 0.1.0
     */
    SendResult send(Email email, SendContext context);

    /**
     * Sends the email without blocking the caller. The default runs {@link #send} on the context's executor;
     * transports with a non-blocking client can override it.
     *
     * @param email   the email
     * @param context the send context
     * @return a stage completed with the result, or exceptionally with the {@link SendFailedException} (which
     *         {@code CompletableFuture} may wrap in a {@code CompletionException})
     * @since 0.1.0
     */
    default CompletionStage<SendResult> sendAsync(final Email email, final SendContext context) {
        return CompletableFuture.supplyAsync(new Supplier<SendResult>() {
            @Override
            public SendResult get() {
                return send(email, context);
            }
        }, context.executor());
    }

    /**
     * Sends several emails. The default calls {@link #send} once per email, in order, and records each failure
     * instead of stopping; transports with a native batch endpoint can override it.
     *
     * @param emails  the emails
     * @param context the send context
     * @return one item per email, in the same order
     * @since 0.1.0
     */
    default List<BatchItemResult> sendBatch(List<Email> emails, SendContext context) {
        List<BatchItemResult> results = new ArrayList<BatchItemResult>(emails.size());
        for (Email email : emails) {
            try {
                results.add(BatchItemResult.succeeded(send(email, context)));
            } catch (SendFailedException failure) {
                results.add(BatchItemResult.failed(failure));
            }
        }
        return results;
    }

    /**
     * Releases connections, threads or tokens the transport holds. The default does nothing.
     *
     * @since 0.1.0
     */
    @Override
    default void close() {
    }
}
