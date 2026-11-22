package io.primelogiclab.mailroom;

/**
 * Provider-specific options attached to an {@link Email}, such as a SendGrid template id or a Resend topic. Each
 * provider module defines its own extension classes and ships them in its own releases; core carries them without
 * knowing what is in them.
 *
 * <pre>{@code
 * Email welcome = Email.builder()
 *     .to(user.email())
 *     .with(SendGridOptions.builder().templateId("d-8f1c...").build())
 *     .build();
 *
 * SendGridOptions options = welcome.extension(SendGridOptions.class).orElse(null);
 * }</pre>
 *
 * <p>An email holds at most one extension of each class. Transports read only the classes they declare that they
 * understand; every other transport ignores them, unless the extension is {@linkplain #essential() essential}.
 *
 * <p>Implementations must be final and immutable, and implement {@code equals} and {@code hashCode}, because emails
 * are values that are compared, copied and sent from several threads.
 *
 * @since 0.1.0
 */
public interface EmailExtension {

    /**
     * Tells whether this email must only go through a transport that understands this extension's class. For example,
     * a provider template id means nothing elsewhere, so failing over to another provider would send an empty email;
     * the router fails with {@code NoEligibleTransportException} instead.
     *
     * @return true to limit the route to transports that understand this class; false by default
     * @since 0.1.0
     */
    default boolean essential() {
        return false;
    }

    /**
     * Tells whether this extension supplies the subject and bodies, as a provider-hosted template does. When any
     * extension on an email returns true, the email may be built without a subject, a text body or an HTML body.
     *
     * @return true if this extension supplies the content; false by default
     * @since 0.1.0
     */
    default boolean providesContent() {
        return false;
    }
}
