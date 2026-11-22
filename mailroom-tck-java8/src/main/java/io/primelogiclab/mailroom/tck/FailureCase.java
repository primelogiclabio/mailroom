package io.primelogiclab.mailroom.tck;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One way a send can fail, for {@link FailureContract} and {@link RedactionContract}: how to make the next send fail
 * (prime a stub server, script a transport), and what the transport must throw.
 *
 * <pre>{@code
 * FailureCase.of("SMTP2GO endpoint not enabled", new Runnable() {
 *     public void run() {
 *         server.enqueue(StubResponse.status(400).json(
 *                 "{\"data\":{\"error_code\":\"E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED\"}}"));
 *     }
 * }, FailureKind.AUTHENTICATION).status(400);
 * }</pre>
 *
 * <p>The status and retry-after are checked only when a case states them. Immutable: the setters return a new case.
 *
 * @since 0.1.0
 */
public final class FailureCase {

    private final String name;
    private final Runnable prime;
    private final FailureKind kind;
    private final Integer status;
    private final Duration retryAfter;
    private final Email email;

    private FailureCase(String name, Runnable prime, FailureKind kind, Integer status, Duration retryAfter,
            Email email) {
        this.name = name;
        this.prime = prime;
        this.kind = kind;
        this.status = status;
        this.retryAfter = retryAfter;
        this.email = email;
    }

    /**
     * Creates a case that sends {@link TckEmails#minimal()}.
     *
     * @param name  what the case is, shown as the test's name
     * @param prime makes the next send fail; run before the transport is created
     * @param kind  the kind the transport must throw
     * @return the case
     * @since 0.1.0
     */
    public static FailureCase of(String name, Runnable prime, FailureKind kind) {
        Objects.requireNonNull(name, "name");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("a failure case needs a name");
        }
        return new FailureCase(name, Objects.requireNonNull(prime, "prime"), Objects.requireNonNull(kind, "kind"),
                null, null, TckEmails.minimal());
    }

    /**
     * Returns a copy that also expects this HTTP status or SMTP reply code.
     *
     * @param status the code, from 100 to 999
     * @return the new case
     * @since 0.1.0
     */
    public FailureCase status(int status) {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("status must be from 100 to 999");
        }
        return new FailureCase(name, prime, kind, status, retryAfter, email);
    }

    /**
     * Returns a copy that also expects this retry-after.
     *
     * @param retryAfter the wait the failure must carry
     * @return the new case
     * @since 0.1.0
     */
    public FailureCase retryAfter(Duration retryAfter) {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        return new FailureCase(name, prime, kind, status, retryAfter, email);
    }

    /**
     * Returns a copy that sends another email.
     *
     * @param email the email to send
     * @return the new case
     * @since 0.1.0
     */
    public FailureCase email(Email email) {
        return new FailureCase(name, prime, kind, status, retryAfter, Objects.requireNonNull(email, "email"));
    }

    /**
     * Returns the case's name.
     *
     * @return the name
     * @since 0.1.0
     */
    public String name() {
        return name;
    }

    /**
     * Makes the next send fail.
     *
     * @since 0.1.0
     */
    public void prime() {
        prime.run();
    }

    /**
     * Returns the kind the transport must throw.
     *
     * @return the kind
     * @since 0.1.0
     */
    public FailureKind kind() {
        return kind;
    }

    /**
     * Returns the status the failure must carry.
     *
     * @return the status, if the case states one
     * @since 0.1.0
     */
    public OptionalInt status() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /**
     * Returns the retry-after the failure must carry.
     *
     * @return the wait, if the case states one
     * @since 0.1.0
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Returns the email the case sends.
     *
     * @return the email
     * @since 0.1.0
     */
    public Email email() {
        return email;
    }

    /**
     * Returns the case's name.
     *
     * @return the name
     */
    @Override
    public String toString() {
        return name;
    }
}
