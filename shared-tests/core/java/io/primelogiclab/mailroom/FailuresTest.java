package io.primelogiclab.mailroom;

import static io.primelogiclab.mailroom.ResultsTest.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** SendFailedException, NoEligibleTransportException and the message-safety rule. */
class FailuresTest {

    @Nested
    class SendFailed {
        @Test
        void carriesEveryField() {
            IOException cause = new IOException("connection reset");
            SendFailedException e = SendFailedException.builder(FailureKind.RATE_LIMITED, "SendGrid answered 429")
                    .transportName("primary").providerId("sendgrid").statusCode(429).providerCode("too_many")
                    .retryAfter(Duration.ofSeconds(1)).cause(cause).build();
            assertThat(e.kind()).isEqualTo(FailureKind.RATE_LIMITED);
            assertThat(e.transportName()).contains("primary");
            assertThat(e.providerId()).contains("sendgrid");
            assertThat(e.statusCode()).hasValue(429);
            assertThat(e.providerCode()).contains("too_many");
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));
            assertThat(e.getCause()).isSameAs(cause);
            assertThat(e.attempts()).isEmpty();
            assertThat(e.getMessage()).isEqualTo("RATE_LIMITED via primary: SendGrid answered 429");
            assertThat(e).isInstanceOf(MailroomException.class);
        }

        @Test
        void onlyKindAndMessageAreRequired() {
            SendFailedException e = SendFailedException.builder(FailureKind.UNKNOWN_OUTCOME, "read timed out").build();
            assertThat(e.getMessage()).isEqualTo("UNKNOWN_OUTCOME: read timed out");
            assertThat(e.transportName()).isEmpty();
            assertThat(e.statusCode()).isEmpty();
            assertThat(e.retryAfter()).isEmpty();
            assertThatThrownBy(() -> SendFailedException.builder(null, "x")).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SendFailedException.builder(FailureKind.REJECTED, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void toBuilderAddsAttemptsAndKeepsTheCause() {
            IOException cause = new IOException("reset");
            SendFailedException first = SendFailedException.builder(FailureKind.UNAVAILABLE, "503")
                    .transportName("primary").statusCode(503).cause(cause).build();
            Attempt attempt = Attempt.failed("primary", "sendgrid", T0, Duration.ofMillis(3), first);
            SendFailedException withAttempts = first.toBuilder().attempts(Collections.singletonList(attempt)).build();
            assertThat(withAttempts.attempts()).containsExactly(attempt);
            assertThat(withAttempts.getCause()).isSameAs(cause);
            assertThat(withAttempts.getMessage()).isEqualTo(first.getMessage());
            assertThat(withAttempts.statusCode()).hasValue(503);
            assertThat(first.attempts()).isEmpty();
            assertThatThrownBy(() -> withAttempts.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rejectsBadCodesAndWaits() {
            assertThatThrownBy(() -> SendFailedException.builder(FailureKind.UNAVAILABLE, "x").statusCode(99))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SendFailedException.builder(FailureKind.UNAVAILABLE, "x").statusCode(1000))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SendFailedException.builder(FailureKind.RATE_LIMITED, "x")
                    .retryAfter(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
            assertThat(SendFailedException.builder(FailureKind.REJECTED, "x").statusCode(550).build().statusCode())
                    .hasValue(550);
        }
    }

    @Nested
    class NoEligible {
        @Test
        void listsSkipReasonsInRouteOrder() {
            Map<String, String> reasons = new LinkedHashMap<String, String>();
            reasons.put("backup", "doesn't understand SendGridOptions (essential)");
            reasons.put("primary", "circuit open");
            NoEligibleTransportException e = new NoEligibleTransportException(reasons);
            assertThat(e.skipReasons()).containsExactly(
                    entry("backup", "doesn't understand SendGridOptions (essential)"), entry("primary", "circuit open"));
            assertThat(e.getMessage()).isEqualTo("No transport can carry this email: "
                    + "backup: doesn't understand SendGridOptions (essential); primary: circuit open");
            assertThat(e).isInstanceOf(MailroomException.class);
        }

        @Test
        void copiesAndLocksTheReasons() {
            Map<String, String> reasons = new LinkedHashMap<String, String>();
            reasons.put("a", "too large");
            NoEligibleTransportException e = new NoEligibleTransportException(reasons);
            reasons.put("b", "later");
            assertThat(e.skipReasons()).hasSize(1);
            assertThatThrownBy(() -> e.skipReasons().put("c", "x")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void needsAtLeastOneReason() {
            assertThatThrownBy(() -> new NoEligibleTransportException(Collections.<String, String>emptyMap()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** The CORE-3 done-when: exception messages carry no full addresses. */
    @Nested
    class MessageSafety {
        private final String[] leaky = {
            "550 5.1.1 player@example.com: user unknown",
            "Recipients <Player.One+golf@Example.COM>, two@example.org rejected",
            "bounce for first.last@sub.example.co.uk."
        };

        @Test
        void sendFailedMasksAddressesInItsMessageAndToString() {
            for (String text : leaky) {
                SendFailedException e = SendFailedException.builder(FailureKind.REJECTED, text).transportName("smtp")
                        .build();
                assertNoFullAddress(e.getMessage());
                assertNoFullAddress(e.toString());
                assertNoFullAddress(e.toBuilder().build().getMessage());
            }
            assertThat(SendFailedException.builder(FailureKind.REJECTED, leaky[0]).build().getMessage())
                    .isEqualTo("REJECTED: 550 5.1.1 p***@example.com: user unknown");
        }

        @Test
        void noEligibleMasksAddressesInReasons() {
            Map<String, String> reasons = new LinkedHashMap<String, String>();
            reasons.put("m365", "precheck: header too long for player@example.com");
            NoEligibleTransportException e = new NoEligibleTransportException(reasons);
            assertNoFullAddress(e.getMessage());
            assertNoFullAddress(e.toString());
            assertNoFullAddress(e.skipReasons().get("m365"));
        }

        @Test
        void resultsAndAttemptsHoldNoAddresses() {
            SendFailedException failure = SendFailedException.builder(FailureKind.REJECTED, leaky[0])
                    .providerCode("unknown_user").build();
            Attempt attempt = Attempt.failed("smtp", "smtp", T0, Duration.ZERO, failure);
            assertNoFullAddress(attempt.toString());
            SendResult result = SendResult.builder().transportName("backup").providerId("smtp2go").acceptedAt(T0)
                    .attempts(Collections.singletonList(attempt)).build();
            assertNoFullAddress(result.toString());
        }

        private void assertNoFullAddress(String text) {
            assertThat(text).doesNotContain("player@").doesNotContainIgnoringCase("player.one+golf@")
                    .doesNotContain("two@").doesNotContain("first.last@");
        }
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return new java.util.AbstractMap.SimpleEntry<String, String>(key, value);
    }
}
