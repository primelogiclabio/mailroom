package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import java.util.Objects;
import java.util.Optional;

/**
 * One email's outcome in {@link MailTransport#sendBatch}: either a {@link SendResult} or the
 * {@link SendFailedException} that email failed with. A batch reports every email instead of stopping at the first
 * failure.
 *
 * @since 0.1.0
 */
public final class BatchItemResult {

    private final SendResult result;
    private final SendFailedException failure;

    private BatchItemResult(SendResult result, SendFailedException failure) {
        this.result = result;
        this.failure = failure;
    }

    /**
     * The email was sent.
     *
     * @param result the result
     * @return the item
     * @since 0.1.0
     */
    public static BatchItemResult succeeded(SendResult result) {
        return new BatchItemResult(Objects.requireNonNull(result, "result"), null);
    }

    /**
     * The email failed.
     *
     * @param failure how it failed
     * @return the item
     * @since 0.1.0
     */
    public static BatchItemResult failed(SendFailedException failure) {
        return new BatchItemResult(null, Objects.requireNonNull(failure, "failure"));
    }

    /**
     * Tells whether the email was sent.
     *
     * @return true on success
     * @since 0.1.0
     */
    public boolean succeeded() {
        return result != null;
    }

    /**
     * Returns the result of a sent email.
     *
     * @return the result; empty if it failed
     * @since 0.1.0
     */
    public Optional<SendResult> result() {
        return Optional.ofNullable(result);
    }

    /**
     * Returns how the email failed.
     *
     * @return the failure; empty if it was sent
     * @since 0.1.0
     */
    public Optional<SendFailedException> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Two items are equal when they hold equal results, or the same failure.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BatchItemResult)) {
            return false;
        }
        BatchItemResult that = (BatchItemResult) other;
        return Objects.equals(result, that.result) && Objects.equals(failure, that.failure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, failure);
    }

    /**
     * Describes the item, such as {@code BatchItemResult[SendResult[...]]} or
     * {@code BatchItemResult[UNAVAILABLE via primary: ...]}.
     *
     * @return a description safe to log
     */
    @Override
    public String toString() {
        return "BatchItemResult[" + (result != null ? result.toString() : failure.getMessage()) + "]";
    }
}
