package io.primelogiclab.mailroom;

/**
 * Hears what happens to each send, for metrics and tracing: which transports were skipped, every attempt, and how
 * the send ended. Wire it to Micrometer, OpenTelemetry or your own counters with
 * {@link MailroomBuilder#listener(MailroomListener)} or {@link Mailroom#fromProperties(java.util.Map, MailroomListener)}.
 *
 * <p>Every method does nothing by default, so a listener overrides only the events it needs. Later releases may add
 * events, such as circuit-breaker changes, as new default methods.
 *
 * <p>Mailroom calls listeners on the sending thread, in the order they were added, while the send is in progress, so
 * a listener must be quick and thread-safe. An exception from a listener is logged and ignored: it never changes the
 * outcome of the send. The {@link Email} passed in is the caller's email; its {@code toString()} is masked, but its
 * accessors return full addresses, so take care what you record.
 *
 * <p>For one send, the events come in this order: an {@link #onSkip} for each transport on the route that can't carry
 * the email, an {@link #onAttempt} after each try, then either {@link #onSuccess} or {@link #onFailure}.
 *
 * @since 0.1.0
 */
public interface MailroomListener {

    /**
     * Called when a transport on the route can't carry the email and is left out, such as one without HTML support
     * for an HTML email.
     *
     * @param email         the email
     * @param transportName the transport's name
     * @param reason        why, such as {@code doesn't support html}; addresses in it are masked
     * @since 0.1.0
     */
    default void onSkip(Email email, String transportName, String reason) {
    }

    /**
     * Called after each attempt, whether it succeeded or failed.
     *
     * @param email   the email
     * @param attempt the attempt: transport, provider, start time, duration and, for a failure, its kind and codes
     * @since 0.1.0
     */
    default void onAttempt(Email email, Attempt attempt) {
    }

    /**
     * Called once when a transport accepted the email.
     *
     * @param email  the email
     * @param result the result, with every attempt
     * @since 0.1.0
     */
    default void onSuccess(Email email, SendResult result) {
    }

    /**
     * Called once when the send failed: a {@link SendFailedException} when transports were tried and none accepted
     * the email, or a {@link NoEligibleTransportException} when none could be tried. The same exception is then thrown
     * to the caller.
     *
     * @param email   the email
     * @param failure the failure
     * @since 0.1.0
     */
    default void onFailure(Email email, MailroomException failure) {
    }
}
