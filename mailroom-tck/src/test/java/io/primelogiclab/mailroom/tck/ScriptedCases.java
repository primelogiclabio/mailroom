package io.primelogiclab.mailroom.tck;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Failure cases for a {@link ScriptedTransport}: each case scripts the first call of the next transport. */
final class ScriptedCases {

    private ScriptedTransport.Builder next = fresh();

    /** The transport for the case just primed; later transports start unscripted again. */
    ScriptedTransport take() {
        ScriptedTransport transport = next.build();
        next = fresh();
        return transport;
    }

    List<FailureCase> all() {
        return Arrays.asList(
                FailureCase.of("invalid message", () -> next.fail(FailureKind.INVALID_MESSAGE, 422),
                        FailureKind.INVALID_MESSAGE).status(422),
                FailureCase.of("rejected", () -> next.fail(FailureKind.REJECTED), FailureKind.REJECTED),
                FailureCase.of("authentication", () -> next.fail(FailureKind.AUTHENTICATION, 401),
                        FailureKind.AUTHENTICATION).status(401),
                FailureCase.of("rate limited", () -> next.rateLimited(Duration.ofSeconds(2)),
                        FailureKind.RATE_LIMITED).status(429).retryAfter(Duration.ofSeconds(2)),
                FailureCase.of("unavailable", () -> next.fail(FailureKind.UNAVAILABLE, 503), FailureKind.UNAVAILABLE)
                        .status(503),
                FailureCase.of("timed out after sending",
                        () -> next.failAfter(Duration.ofMillis(5), FailureKind.UNKNOWN_OUTCOME),
                        FailureKind.UNKNOWN_OUTCOME));
    }

    private static ScriptedTransport.Builder fresh() {
        return ScriptedTransport.builder("scripted-tck");
    }
}
