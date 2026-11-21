package io.primelogiclab.mailroom;

/**
 * Base class of every exception mailroom throws. It is unchecked, so sending works in lambdas and streams.
 *
 * <p>Messages never contain secrets, full email addresses, subjects or bodies, so they are safe to log.
 *
 * @since 0.1.0
 */
public class MailroomException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message what went wrong, without personal data or secrets
     * @since 0.1.0
     */
    public MailroomException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and its cause.
     *
     * @param message what went wrong, without personal data or secrets
     * @param cause   the underlying failure
     * @since 0.1.0
     */
    public MailroomException(String message, Throwable cause) {
        super(message, cause);
    }
}
