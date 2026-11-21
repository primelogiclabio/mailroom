package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FailureKind, Status, Attempt and SendResult. */
class ResultsTest {

    static final Instant T0 = Instant.parse("2026-11-21T12:00:00Z");

    static SendFailedException unavailable() {
        return SendFailedException.builder(FailureKind.UNAVAILABLE, "SendGrid answered 503")
                .transportName("primary").providerId("sendgrid").statusCode(503).providerCode("server_error").build();
    }

    @Nested
    class FailureKinds {
        @Test
        void areTheDesignsSixInOrder() {
            assertThat(FailureKind.values()).containsExactly(FailureKind.INVALID_MESSAGE, FailureKind.REJECTED,
                    FailureKind.AUTHENTICATION, FailureKind.RATE_LIMITED, FailureKind.UNAVAILABLE,
                    FailureKind.UNKNOWN_OUTCOME);
        }

        @Test
        void aSwitchCanCoverEveryKind() {
            for (FailureKind kind : FailureKind.values()) {
                assertThat(failsOverByDefault(kind)).isNotNull();
            }
        }

        /** The design's failover column; a Java 21 switch expression over the enum would be checked for completeness. */
        private Boolean failsOverByDefault(FailureKind kind) {
            switch (kind) {
                case INVALID_MESSAGE:
                case UNKNOWN_OUTCOME:
                    return false;
                case REJECTED:
                case AUTHENTICATION:
                case RATE_LIMITED:
                case UNAVAILABLE:
                    return true;
                default:
                    return null;
            }
        }

        @Test
        void statusHasAcceptedAndScheduled() {
            assertThat(Status.values()).containsExactly(Status.ACCEPTED, Status.SCHEDULED);
        }
    }

    @Nested
    class Attempts {
        @Test
        void aSuccessHasNoFailureFields() {
            Attempt a = Attempt.succeeded("backup", "smtp2go", T0, Duration.ofMillis(120));
            assertThat(a.succeeded()).isTrue();
            assertThat(a.transportName()).isEqualTo("backup");
            assertThat(a.providerId()).isEqualTo("smtp2go");
            assertThat(a.startedAt()).isEqualTo(T0);
            assertThat(a.duration()).isEqualTo(Duration.ofMillis(120));
            assertThat(a.failureKind()).isEmpty();
            assertThat(a.statusCode()).isEmpty();
            assertThat(a.providerCode()).isEmpty();
            assertThat(a.toString()).isEqualTo("Attempt[backup/smtp2go SUCCEEDED in PT0.12S]");
        }

        @Test
        void aFailureCopiesTheCodesButNotTheMessage() {
            Attempt a = Attempt.failed("primary", "sendgrid", T0, Duration.ofMillis(200), unavailable());
            assertThat(a.succeeded()).isFalse();
            assertThat(a.failureKind()).contains(FailureKind.UNAVAILABLE);
            assertThat(a.statusCode()).hasValue(503);
            assertThat(a.providerCode()).contains("server_error");
            assertThat(a.toString()).isEqualTo("Attempt[primary/sendgrid UNAVAILABLE status=503 code=server_error in PT0.2S]")
                    .doesNotContain("answered");
        }

        @Test
        void equalityComparesEveryField() {
            assertThat(Attempt.succeeded("a", "p", T0, Duration.ZERO))
                    .isEqualTo(Attempt.succeeded("a", "p", T0, Duration.ZERO))
                    .hasSameHashCodeAs(Attempt.succeeded("a", "p", T0, Duration.ZERO))
                    .isNotEqualTo(Attempt.succeeded("b", "p", T0, Duration.ZERO));
        }

        @Test
        void rejectsNullsAndNegativeDurations() {
            assertThatThrownBy(() -> Attempt.succeeded(null, "p", T0, Duration.ZERO))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Attempt.succeeded("a", "p", T0, Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Attempt.failed("a", "p", T0, Duration.ZERO, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class SendResults {
        @Test
        void defaultsToAcceptedWithNoAttemptsOrMessageId() {
            SendResult r = SendResult.builder().transportName("primary").providerId("sendgrid").acceptedAt(T0).build();
            assertThat(r.status()).isEqualTo(Status.ACCEPTED);
            assertThat(r.attempts()).isEmpty();
            assertThat(r.providerMessageId()).isEmpty();
            assertThat(r.acceptedAt()).isEqualTo(T0);
            assertThat(r.toString())
                    .isEqualTo("SendResult[transport=primary, provider=sendgrid, status=ACCEPTED, messageId=none, attempts=0]");
        }

        @Test
        void carriesEveryField() {
            Attempt failed = Attempt.failed("primary", "sendgrid", T0, Duration.ofMillis(5), unavailable());
            Attempt ok = Attempt.succeeded("backup", "smtp2go", T0.plusMillis(5), Duration.ofMillis(9));
            SendResult r = SendResult.builder().transportName("backup").providerId("smtp2go")
                    .providerMessageId("1rXv5-abc").status(Status.SCHEDULED).acceptedAt(T0)
                    .attempts(Arrays.asList(failed, ok)).build();
            assertThat(r.transportName()).isEqualTo("backup");
            assertThat(r.providerId()).isEqualTo("smtp2go");
            assertThat(r.providerMessageId()).contains("1rXv5-abc");
            assertThat(r.status()).isEqualTo(Status.SCHEDULED);
            assertThat(r.attempts()).containsExactly(failed, ok);
            assertThat(r.toString()).contains("messageId=1rXv5-abc").contains("attempts=2");
        }

        @Test
        void toBuilderReplacesTheAttempts() {
            SendResult r = SendResult.builder().transportName("t").providerId("p").acceptedAt(T0).build();
            Attempt ok = Attempt.succeeded("t", "p", T0, Duration.ZERO);
            SendResult withAttempts = r.toBuilder().attempts(Collections.singletonList(ok)).build();
            assertThat(withAttempts.attempts()).containsExactly(ok);
            assertThat(r.attempts()).isEmpty();
            assertThat(withAttempts).isNotEqualTo(r);
            assertThat(withAttempts.toBuilder().build()).isEqualTo(withAttempts).hasSameHashCodeAs(withAttempts);
        }

        @Test
        void requiresTransportProviderAndTime() {
            assertThatThrownBy(() -> SendResult.builder().providerId("p").acceptedAt(T0).build())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> SendResult.builder().transportName("t").acceptedAt(T0).build())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> SendResult.builder().transportName("t").providerId("p").build())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> SendResult.builder().providerMessageId(" "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void attemptsCannotBeChanged() {
            SendResult r = SendResult.builder().transportName("t").providerId("p").acceptedAt(T0).build();
            assertThatThrownBy(() -> r.attempts().add(Attempt.succeeded("t", "p", T0, Duration.ZERO)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
