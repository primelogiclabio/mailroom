package io.primelogiclab.mailroom.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdkAndDurationsTest {

    private static int javaFeatureVersion() {
        String spec = System.getProperty("java.specification.version");
        return Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
    }

    @Test
    void virtualThreadsAreAvailableExactlyFromJava21() throws Exception {
        ExecutorService executor = Jdk.newVirtualThreadExecutor();
        if (javaFeatureVersion() >= 21) {
            assertThat(executor).isNotNull();
            try {
                Future<Boolean> virtual = executor.submit(() -> Jdk.isVirtual(Thread.currentThread()));
                assertThat(virtual.get(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }
        } else {
            assertThat(executor).isNull();
        }
        assertThat(Jdk.isVirtual(Thread.currentThread())).isFalse();
    }

    @Test
    void theDefaultExecutorMatchesTheRuntime() throws Exception {
        ExecutorService executor = DefaultMailroom.defaultExecutor();
        try {
            Thread ran = executor.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
            assertThat(Jdk.isVirtual(ran)).isEqualTo(javaFeatureVersion() >= 21);
            if (javaFeatureVersion() < 21) {
                assertThat(ran.getName()).startsWith("mailroom-send-");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void parsesDurations() {
        assertThat(Durations.parse("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(Durations.parse(" 30S ")).isEqualTo(Duration.ofSeconds(30));
        assertThat(Durations.parse("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Durations.parse("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(Durations.parse("2d")).isEqualTo(Duration.ofDays(2));
        assertThat(Durations.parse("PT1M30S")).isEqualTo(Duration.ofSeconds(90));
        assertThat(Durations.parse("0s")).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "30", "-1s", "PT-1S", "soon", "1.5s", "99999999999999999999s"})
    void rejectsOtherText(String text) {
        assertThat(Durations.parse(text)).isNull();
    }

    @Test
    void nullIsNotADuration() {
        assertThat(Durations.parse(null)).isNull();
    }
}
