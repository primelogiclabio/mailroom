package io.primelogiclab.mailroom.internal;

import io.primelogiclab.mailroom.Attempt;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.MailroomException;
import io.primelogiclab.mailroom.MailroomListener;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs what happens to each send and tells the listeners. Not API: this package may change in any release.
 *
 * <p>Rules that keep logs free of personal data and secrets:
 * <ul>
 *   <li>lines carry transport names, provider ids, failure kinds, codes, counts and message ids, never recipients,
 *       subjects, bodies, headers or secrets;</li>
 *   <li>text that comes from a transport (skip reasons, failure messages) is masked with {@link Masking};</li>
 *   <li>no {@link Throwable} is ever attached to a record, because a transport's cause isn't masked;</li>
 *   <li>messages are plain strings, never {@code MessageFormat} patterns, so an apostrophe in a reason can't break
 *       them.</li>
 * </ul>
 */
public final class Observer {

    /** The logger core writes to. Its name is documented and stays the same across releases. */
    public static final String LOGGER_NAME = "io.primelogiclab.mailroom";

    private static final Logger LOG = Logger.getLogger(LOGGER_NAME);

    private final List<MailroomListener> listeners;

    /**
     * Creates an observer.
     *
     * @param listeners the listeners, called in this order
     */
    public Observer(List<MailroomListener> listeners) {
        this.listeners = Collections.unmodifiableList(new ArrayList<MailroomListener>(listeners));
    }

    /**
     * A transport on the route can't carry the email.
     *
     * @param email         the email
     * @param transportName the transport
     * @param reason        why, already masked
     */
    void skipped(Email email, String transportName, String reason) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "skipped " + transportName + ": " + reason);
        }
        for (MailroomListener listener : listeners) {
            try {
                listener.onSkip(email, transportName, reason);
            } catch (RuntimeException e) {
                listenerFailed(listener, "onSkip", e);
            }
        }
    }

    /**
     * An attempt finished.
     *
     * @param email   the email
     * @param number  which attempt on this transport, from 1
     * @param attempt the attempt
     * @param failure the failure, or null if it succeeded
     * @param next    what the router does next, such as {@code retrying in 250 ms}, or null if it succeeded
     */
    void attempted(Email email, int number, Attempt attempt, SendFailedException failure, String next) {
        if (LOG.isLoggable(Level.FINE)) {
            StringBuilder line = new StringBuilder("attempt ").append(number).append(" on ")
                    .append(attempt.transportName()).append(" (").append(attempt.providerId()).append(") ");
            if (failure == null) {
                line.append("succeeded in ").append(attempt.duration().toMillis()).append(" ms");
            } else {
                line.append("failed after ").append(attempt.duration().toMillis()).append(" ms: ")
                        .append(Masking.addressesIn(failure.getMessage()));
                if (failure.statusCode().isPresent()) {
                    line.append(" (status ").append(failure.statusCode().getAsInt()).append(')');
                }
                if (failure.providerCode().isPresent()) {
                    line.append(" (code ").append(Masking.addressesIn(failure.providerCode().get())).append(')');
                }
                line.append("; ").append(next);
            }
            LOG.log(Level.FINE, line.toString());
        }
        for (MailroomListener listener : listeners) {
            try {
                listener.onAttempt(email, attempt);
            } catch (RuntimeException e) {
                listenerFailed(listener, "onAttempt", e);
            }
        }
    }

    /**
     * A transport accepted the email.
     *
     * @param email  the email
     * @param result the result, with every attempt
     */
    void succeeded(Email email, SendResult result) {
        if (LOG.isLoggable(Level.INFO)) {
            int attempts = result.attempts().size();
            LOG.log(Level.INFO, "sent via " + result.transportName() + " (" + result.providerId() + ") after "
                    + attempts + (attempts == 1 ? " attempt" : " attempts") + ", messageId "
                    + result.providerMessageId().orElse("none"));
        }
        for (MailroomListener listener : listeners) {
            try {
                listener.onSuccess(email, result);
            } catch (RuntimeException e) {
                listenerFailed(listener, "onSuccess", e);
            }
        }
    }

    /**
     * The send failed; the caller gets this exception next.
     *
     * @param email   the email
     * @param failure a {@link SendFailedException} or a {@link NoEligibleTransportException}
     */
    void failed(Email email, MailroomException failure) {
        if (LOG.isLoggable(Level.WARNING)) {
            LOG.log(Level.WARNING, describe(failure));
        }
        for (MailroomListener listener : listeners) {
            try {
                listener.onFailure(email, failure);
            } catch (RuntimeException e) {
                listenerFailed(listener, "onFailure", e);
            }
        }
    }

    private static String describe(MailroomException failure) {
        if (failure instanceof NoEligibleTransportException) {
            StringBuilder line = new StringBuilder("no eligible transport: ");
            String separator = "";
            for (Map.Entry<String, String> entry
                    : ((NoEligibleTransportException) failure).skipReasons().entrySet()) {
                line.append(separator).append(entry.getKey()).append(' ').append(entry.getValue());
                separator = "; ";
            }
            return line.toString();
        }
        SendFailedException sendFailed = (SendFailedException) failure;
        List<Attempt> attempts = sendFailed.attempts();
        Set<String> transports = new LinkedHashSet<String>();
        for (Attempt attempt : attempts) {
            transports.add(attempt.transportName());
        }
        StringBuilder line = new StringBuilder("send failed: ").append(sendFailed.kind().name()).append(" after ")
                .append(attempts.size()).append(attempts.size() == 1 ? " attempt" : " attempts");
        if (!transports.isEmpty()) {
            line.append(" (");
            String separator = "";
            for (String transport : transports) {
                line.append(separator).append(transport);
                separator = ", ";
            }
            line.append(')');
        }
        return line.toString();
    }

    private static void listenerFailed(MailroomListener listener, String event, RuntimeException e) {
        if (LOG.isLoggable(Level.WARNING)) {
            LOG.log(Level.WARNING, "listener " + listener.getClass().getName() + " threw "
                    + e.getClass().getName() + " in " + event + "; ignored");
        }
    }
}
