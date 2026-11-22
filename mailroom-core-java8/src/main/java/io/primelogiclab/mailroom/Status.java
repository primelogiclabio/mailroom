package io.primelogiclab.mailroom;

/**
 * What the provider did with a message that was sent successfully. Neither value means "delivered": the provider has
 * taken responsibility for the message, and delivery happens later.
 *
 * @since 0.1.0
 */
public enum Status {

    /**
     * The provider accepted the message for sending now.
     *
     * @since 0.1.0
     */
    ACCEPTED,

    /**
     * The provider accepted the message and will send it at the email's {@code sendAt} time.
     *
     * @since 0.1.0
     */
    SCHEDULED
}
