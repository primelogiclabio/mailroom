package io.primelogiclab.mailroom;

/**
 * The one classification every send failure is sorted into. Each provider module maps its own error responses onto
 * these kinds, and the contract tests check that mapping. The router uses the kind to decide whether to retry, fail
 * over or stop; the caller's job queue can use it to decide whether to try again later.
 *
 * <p>Because this is an enum, a Java 21 {@code switch} expression over it fails to compile if a kind is missing, which
 * keeps handling code complete without a sealed exception hierarchy.
 *
 * @since 0.1.0
 */
public enum FailureKind {

    /**
     * The message itself is wrong: mailroom's validation, or the provider rejects the content (a bad address, too
     * large). Not retried on the same transport, and not failed over, because another provider would reject it too.
     *
     * @since 0.1.0
     */
    INVALID_MESSAGE,

    /**
     * The provider refuses this sender or message by policy, for example an unverified sender. Not retried on the same
     * transport; fails over to the next one.
     *
     * @since 0.1.0
     */
    REJECTED,

    /**
     * The credentials don't work: HTTP 401 or 403, an expired secret, a missing permission. Not retried; fails over,
     * and deserves an alert because it won't fix itself.
     *
     * @since 0.1.0
     */
    AUTHENTICATION,

    /**
     * The provider is throttling: HTTP 429, a quota. Retried on the same transport only after its
     * {@code Retry-After} time; otherwise fails over.
     *
     * @since 0.1.0
     */
    RATE_LIMITED,

    /**
     * The provider can't be reached or is failing: HTTP 5xx, connection refused, DNS or TLS failure. Retried once on
     * the same transport after a short backoff, then fails over.
     *
     * @since 0.1.0
     */
    UNAVAILABLE,

    /**
     * It is unclear whether the provider accepted the message: a timeout after the request was sent, or an error after
     * which the mail may still go out. Not retried, and by default not failed over, because a second provider could
     * deliver a duplicate; failing over is safe only with an idempotency key the next provider honours.
     *
     * @since 0.1.0
     */
    UNKNOWN_OUTCOME
}
