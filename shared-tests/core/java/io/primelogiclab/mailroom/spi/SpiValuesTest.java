package io.primelogiclab.mailroom.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ConfigKey, SendContext, Precheck and BatchItemResult. */
class SpiValuesTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Nested
    class ConfigKeys {
        @Test
        void fluentMethodsReturnChangedCopies() {
            ConfigKey base = ConfigKey.of("api-key");
            ConfigKey full = base.required().secret().description("The API key").withDefault("x");
            assertThat(base.isRequired()).isFalse();
            assertThat(base.isSecret()).isFalse();
            assertThat(base.description()).isEmpty();
            assertThat(base.defaultValue()).isEmpty();
            assertThat(full.isRequired()).isTrue();
            assertThat(full.isSecret()).isTrue();
            assertThat(full.description()).contains("The API key");
            assertThat(full.defaultValue()).contains("x");
            assertThat(full).isEqualTo(base).hasSameHashCodeAs(base);
            assertThat(full.toString()).isEqualTo("api-key");
        }

        @ParameterizedTest
        @ValueSource(strings = {"api-key", "tenant-id", "oauth.client-id", "port", "x1"})
        void acceptsKebabCaseNames(String name) {
            assertThat(ConfigKey.of(name).name()).isEqualTo(name);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "apiKey", "api_key", "API-KEY", "-key", "key-", "a..b", "a key"})
        void rejectsOtherNames(String name) {
            assertThatThrownBy(() -> ConfigKey.of(name)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class SendContexts {
        @Test
        void hasSafeDefaults() {
            SendContext c = SendContext.builder().build();
            assertThat(c.attempt()).isEqualTo(1);
            assertThat(c.deadline()).isEmpty();
            assertThat(c.timeLeft()).isEmpty();
            assertThat(c.clock().getZone()).isEqualTo(ZoneOffset.UTC);
            final Thread caller = Thread.currentThread();
            final Thread[] ran = new Thread[1];
            c.executor().execute(new Runnable() {
                @Override
                public void run() {
                    ran[0] = Thread.currentThread();
                }
            });
            assertThat(ran[0]).isSameAs(caller);
        }

        @Test
        void measuresTimeLeftWithItsClock() {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            SendContext c = SendContext.builder().attempt(2).clock(clock).deadline(NOW.plusSeconds(30)).build();
            assertThat(c.attempt()).isEqualTo(2);
            assertThat(c.timeLeft()).contains(Duration.ofSeconds(30));
            assertThat(c.toString()).isEqualTo("SendContext[attempt=2, deadline=2026-11-21T12:00:30Z]");
            SendContext late = SendContext.builder().clock(clock).deadline(NOW.minusSeconds(1)).build();
            assertThat(late.timeLeft()).contains(Duration.ZERO);
        }

        @Test
        void rejectsBadValues() {
            assertThatThrownBy(() -> SendContext.builder().attempt(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SendContext.builder().executor(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SendContext.builder().clock(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void comparesByValue() {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            SendContext c = SendContext.builder().attempt(2).clock(clock).deadline(NOW.plusSeconds(30)).build();
            SendContext same = SendContext.builder().attempt(2).clock(clock).deadline(NOW.plusSeconds(30)).build();
            assertThat(c).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(SendContext.builder().build()).isEqualTo(SendContext.builder().build());
            assertThat(c).isNotEqualTo(
                    SendContext.builder().attempt(3).clock(clock).deadline(NOW.plusSeconds(30)).build());
            assertThat(c).isNotEqualTo(SendContext.builder().attempt(2).clock(clock).build());
            assertThat(c).isNotEqualTo(SendContext.builder().attempt(2).clock(clock).deadline(NOW.plusSeconds(30))
                    .executor(Runnable::run).build());
        }
    }

    @Nested
    class Prechecks {
        @Test
        void okPasses() {
            assertThat(Precheck.ok().passed()).isTrue();
            assertThat(Precheck.ok().reason()).isEmpty();
            assertThat(Precheck.ok().toString()).isEqualTo("Precheck[ok]");
        }

        @Test
        void rejectKeepsAMaskedReason() {
            Precheck p = Precheck.reject("  header too long for player@example.com ");
            assertThat(p.passed()).isFalse();
            assertThat(p.reason()).contains("header too long for p***@example.com");
            assertThat(p.toString()).isEqualTo("Precheck[rejected: header too long for p***@example.com]");
            assertThat(p).isEqualTo(Precheck.reject("header too long for player@example.com"));
        }

        @Test
        void rejectNeedsAReason() {
            assertThatThrownBy(() -> Precheck.reject(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Precheck.reject(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class BatchItems {
        @Test
        void holdsAResultOrAFailure() {
            SendResult result = SendResult.builder().transportName("t").providerId("p").acceptedAt(NOW).build();
            SendFailedException failure = SendFailedException.builder(FailureKind.REJECTED, "no").build();
            BatchItemResult ok = BatchItemResult.succeeded(result);
            BatchItemResult bad = BatchItemResult.failed(failure);
            assertThat(ok.succeeded()).isTrue();
            assertThat(ok.result()).containsSame(result);
            assertThat(ok.failure()).isEmpty();
            assertThat(bad.succeeded()).isFalse();
            assertThat(bad.failure()).containsSame(failure);
            assertThat(bad.result()).isEmpty();
            assertThat(bad.toString()).isEqualTo("BatchItemResult[REJECTED: no]");
            assertThatThrownBy(() -> BatchItemResult.succeeded(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void comparesByValue() {
            SendResult result = SendResult.builder().transportName("t").providerId("p").acceptedAt(NOW).build();
            SendResult equalResult = SendResult.builder().transportName("t").providerId("p").acceptedAt(NOW).build();
            SendFailedException failure = SendFailedException.builder(FailureKind.REJECTED, "no").build();
            BatchItemResult ok = BatchItemResult.succeeded(result);
            BatchItemResult equalOk = BatchItemResult.succeeded(equalResult);
            assertThat(ok).isEqualTo(equalOk).hasSameHashCodeAs(equalOk);
            assertThat(BatchItemResult.failed(failure)).isEqualTo(BatchItemResult.failed(failure));
            // exceptions compare by identity, as Throwable does
            SendFailedException sameText = SendFailedException.builder(FailureKind.REJECTED, "no").build();
            assertThat(BatchItemResult.failed(failure)).isNotEqualTo(BatchItemResult.failed(sameText));
            assertThat(ok).isNotEqualTo(BatchItemResult.failed(failure));
        }
    }
}
