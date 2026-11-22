package io.primelogiclab.mailroom;

/**
 * Base class of every exception mailroom throws. It is unchecked, so sending works in lambdas and streams.
 *
 * <p>Messages never contain secrets, full email addresses, subjects or bodies, so they are safe to log.
 *
 * <p>The hierarchy is sealed: its four subclasses are the only direct ones, and each of them can still be extended.
 *
 * @since 0.1.0
 */
public sealed class MailroomException extends RuntimeException
        permits InvalidConfigException, InvalidEmailException, NoEligibleTransportException, SendFailedException {

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
