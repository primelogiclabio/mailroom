package io.primelogiclab.mailroom;

/**
 * Thrown when an email could not be built or sent. When sending failed after all retry
 * attempts, the last failure is the cause and earlier failures are attached as suppressed exceptions.
 */
public class MailException extends RuntimeException {

    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}
