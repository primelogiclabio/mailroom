package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import java.util.Objects;
import java.util.Optional;

/**
 * One email's outcome in {@link MailTransport#sendBatch}: either a {@link SendResult} or the
 * {@link SendFailedException} that email failed with. A batch reports every email instead of stopping at the first
 * failure. Create one with {@link #succeeded} or {@link #failed}.
 *
 * @param result  the result, for an email that was sent
 * @param failure the failure, for an email that wasn't
 * @since 0.1.0
 */
public record BatchItemResult(Optional<SendResult> result, Optional<SendFailedException> failure) {

    /**
     * Checks that exactly one of the two is present.
     *
     * @throws IllegalArgumentException if both or neither are present
     * @since 0.1.0
     */
    public BatchItemResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(failure, "failure");
        if (result.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("a batch item has either a result or a failure");
        }
    }

    /**
     * The email was sent.
     *
     * @param result its result
     * @return the item
     * @since 0.1.0
     */
    public static BatchItemResult succeeded(SendResult result) {
        return new BatchItemResult(Optional.of(result), Optional.empty());
    }

    /**
     * The email failed.
     *
     * @param failure how it failed
     * @return the item
     * @since 0.1.0
     */
    public static BatchItemResult failed(SendFailedException failure) {
        return new BatchItemResult(Optional.empty(), Optional.of(failure));
    }

    /**
     * Tells whether the email was sent.
     *
     * @return true when there is a result
     * @since 0.1.0
     */
    public boolean succeeded() {
        return result.isPresent();
    }

    /**
     * Describes the item by its result or its failure's message, which masks addresses.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "BatchItemResult[" + result.map(SendResult::toString).orElseGet(() -> failure.get().getMessage()) + "]";
    }
}
