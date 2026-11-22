package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FailureMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CANARY = "canary.person@example.com";

    private static final FailureMapper.CodeReader SMTP2GO_CODE = new FailureMapper.CodeReader() {
        @Override
        public Optional<String> read(JsonValue body) {
            return body.get("data").get("error_code").asString();
        }
    };

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "100, UNAVAILABLE", "101, UNAVAILABLE", "199, UNAVAILABLE",
            "300, UNAVAILABLE", "301, UNAVAILABLE", "302, UNAVAILABLE", "304, UNAVAILABLE", "308, UNAVAILABLE",
            "400, INVALID_MESSAGE", "413, INVALID_MESSAGE", "422, INVALID_MESSAGE",
            "401, AUTHENTICATION", "403, AUTHENTICATION", "407, AUTHENTICATION",
            "408, UNAVAILABLE", "425, UNAVAILABLE",
            "409, UNKNOWN_OUTCOME",
            "429, RATE_LIMITED",
            "402, REJECTED", "404, REJECTED", "405, REJECTED", "406, REJECTED", "410, REJECTED", "411, REJECTED",
            "415, REJECTED", "421, REJECTED", "451, REJECTED", "499, REJECTED",
            "500, UNAVAILABLE", "501, UNAVAILABLE", "502, UNAVAILABLE", "503, UNAVAILABLE", "505, UNAVAILABLE",
            "520, UNAVAILABLE", "599, UNAVAILABLE",
            "504, UNKNOWN_OUTCOME",
            "600, UNKNOWN_OUTCOME", "999, UNKNOWN_OUTCOME",
    })
    void defaultTableCoversEveryStatusFamily(int status, FailureKind kind) {
        assertThat(FailureMapper.defaultKind(status)).isEqualTo(kind);
        SendFailedException failure = FailureMapper.builder("acme").build().failure(response(status, ""), CLOCK);
        assertThat(failure.kind()).isEqualTo(kind);
        assertThat(failure.statusCode()).hasValue(status);
    }

    @ParameterizedTest(name = "{0} is not a failure status")
    @ValueSource(ints = {200, 202, 204, 299, 99, 1000, -1})
    void refusesSuccessAndOutOfRangeStatuses(int status) {
        assertThatThrownBy(() -> FailureMapper.defaultKind(status)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailureMapper.builder("acme").kind(status, FailureKind.REJECTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSuccessResponseIsNotAFailure() {
        assertThatThrownBy(() -> FailureMapper.builder("acme").build().failure(response(202, ""), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a 202 response is not a failure");
    }

    @Test
    void buildsTheFailureWithStatusProviderAndCode() {
        FailureMapper mapper = FailureMapper.builder("smtp2go").providerCode(SMTP2GO_CODE).build();
        SendFailedException failure = mapper.failure(response(400, "{\"request_id\":\"r\",\"data\":{\"error\":"
                + "\"Invalid recipient " + CANARY + "\",\"error_code\":\"E_ApiResponseCodes.NON_VALIDATING_IN_PAYLOAD\""
                + "}}"), CLOCK);

        assertThat(failure.kind()).isEqualTo(FailureKind.INVALID_MESSAGE);
        assertThat(failure.getMessage())
                .isEqualTo("INVALID_MESSAGE: smtp2go answered 400 (E_ApiResponseCodes.NON_VALIDATING_IN_PAYLOAD)");
        assertThat(failure.statusCode()).hasValue(400);
        assertThat(failure.providerId()).contains("smtp2go");
        assertThat(failure.providerCode()).contains("E_ApiResponseCodes.NON_VALIDATING_IN_PAYLOAD");
        assertThat(failure.transportName()).isEmpty();
        assertThat(failure.retryAfter()).isEmpty();
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void neverPutsTheBodyInTheFailure() {
        FailureMapper mapper = FailureMapper.builder("acme").providerCode(new FailureMapper.CodeReader() {
            @Override
            public Optional<String> read(JsonValue body) {
                return body.get("message").asString();      // a careless reader handing over message text
            }
        }).build();

        for (String body : new String[] {"<html>" + CANARY + "</html>", "{\"message\":\"Invalid to: " + CANARY + "\"}",
                "{\"message\":\"" + CANARY + "\"}", "{\"message\":\"" + repeat('x', 101) + "\"}"}) {
            SendFailedException failure = mapper.failure(response(422, body), CLOCK);
            assertThat(failure.getMessage()).as(body).isEqualTo("INVALID_MESSAGE: acme answered 422");
            assertThat(failure.providerCode()).as(body).isEmpty();
        }
        assertThat(mapper.failure(response(422, "{\"message\":\"" + repeat('x', 100) + "\"}"), CLOCK).providerCode())
                .contains(repeat('x', 100));
    }

    @Test
    void retryAfterComesFromTheHeaderThenAnExhaustedRateLimit() {
        FailureMapper mapper = FailureMapper.builder("resend").build();

        assertThat(mapper.failure(response(429, "", "Retry-After", "2"), CLOCK).retryAfter())
                .contains(Duration.ofSeconds(2));
        assertThat(mapper.failure(response(429, "", "Retry-After", "Sun, 22 Nov 2026 10:00:05 GMT"), CLOCK)
                .retryAfter()).contains(Duration.ofSeconds(5));
        assertThat(mapper.failure(response(429, "", "Retry-After", "3", "RateLimit-Remaining", "0",
                "RateLimit-Reset", "9"), CLOCK).retryAfter()).contains(Duration.ofSeconds(3));
        assertThat(mapper.failure(response(429, "", "RateLimit-Remaining", "0", "RateLimit-Reset", "9"), CLOCK)
                .retryAfter()).contains(Duration.ofSeconds(9));
        assertThat(mapper.failure(response(429, "", "X-RateLimit-Remaining", "0",
                "X-RateLimit-Reset", String.valueOf(NOW.getEpochSecond() + 4)), CLOCK).retryAfter())
                .contains(Duration.ofSeconds(4));
        assertThat(mapper.failure(response(429, "", "RateLimit-Remaining", "3", "RateLimit-Reset", "9"), CLOCK)
                .retryAfter()).isEmpty();
        assertThat(mapper.failure(response(429, ""), CLOCK).retryAfter()).isEmpty();
        assertThat(mapper.failure(response(429, "", "Retry-After", "later"), CLOCK).retryAfter()).isEmpty();

        SendFailedException unavailable = mapper.failure(response(503, "", "Retry-After", "120",
                "RateLimit-Remaining", "0", "RateLimit-Reset", "9"), CLOCK);
        assertThat(unavailable.kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(unavailable.retryAfter()).contains(Duration.ofMinutes(2));
        assertThat(mapper.failure(response(503, "", "RateLimit-Remaining", "0", "RateLimit-Reset", "9"), CLOCK)
                .retryAfter()).isEmpty();
    }

    @Test
    void overridesApplyRuleFirstThenStatusAndCodeThenStatus() {
        FailureMapper mapper = FailureMapper.builder("acme")
                .providerCode(SMTP2GO_CODE)
                .rule(new FailureMapper.Rule() {
                    @Override
                    public Optional<FailureKind> kind(HttpResponse response, Optional<JsonValue> body,
                            Optional<String> providerCode) {
                        boolean unverified = response.statusCode() == 403 && body.isPresent()
                                && body.get().get("data").get("error").asString().orElse("").contains("verified");
                        return unverified ? Optional.of(FailureKind.REJECTED) : Optional.<FailureKind>empty();
                    }
                })
                .kind(403, "E_SENDER", FailureKind.INVALID_MESSAGE)
                .kind(400, "E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED", FailureKind.AUTHENTICATION)
                .kind(400, FailureKind.REJECTED)
                .kind(503, FailureKind.UNKNOWN_OUTCOME)
                .build();

        assertThat(kind(mapper, 403, "{\"data\":{\"error\":\"sender not verified\",\"error_code\":\"E_SENDER\"}}"))
                .isEqualTo(FailureKind.REJECTED);
        assertThat(kind(mapper, 403, "{\"data\":{\"error\":\"other\",\"error_code\":\"E_SENDER\"}}"))
                .isEqualTo(FailureKind.INVALID_MESSAGE);
        assertThat(kind(mapper, 403, "{\"data\":{\"error\":\"other\",\"error_code\":\"E_OTHER\"}}"))
                .isEqualTo(FailureKind.AUTHENTICATION);
        assertThat(kind(mapper, 400, "{\"data\":{\"error_code\":\"E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED\"}}"))
                .isEqualTo(FailureKind.AUTHENTICATION);
        assertThat(kind(mapper, 400, "{\"data\":{\"error_code\":\"E_ApiResponseCodes.OTHER\"}}"))
                .isEqualTo(FailureKind.REJECTED);
        assertThat(kind(mapper, 400, "not json")).isEqualTo(FailureKind.REJECTED);
        assertThat(kind(mapper, 503, "")).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
        assertThat(kind(mapper, 500, "")).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(mapper.toString()).isEqualTo("FailureMapper[acme, 1 rule, 4 overrides]");
    }

    @Test
    void rulesRunInTheOrderAdded() {
        FailureMapper mapper = FailureMapper.builder("acme").rule(always(FailureKind.REJECTED))
                .rule(always(FailureKind.AUTHENTICATION)).build();
        assertThat(kind(mapper, 500, "")).isEqualTo(FailureKind.REJECTED);
        assertThat(mapper.toString()).isEqualTo("FailureMapper[acme, 2 rules, 0 overrides]");
    }

    @Test
    void catchesMistakesInTheProvidersSetup() {
        assertThatThrownBy(() -> FailureMapper.builder(" ")).hasMessage("providerId must not be empty");
        assertThatThrownBy(() -> FailureMapper.builder("ac\nme"))
                .hasMessage("providerId must not have control characters");
        assertThatThrownBy(() -> FailureMapper.builder("acme").kind(409, FailureKind.REJECTED)
                .kind(409, FailureKind.UNAVAILABLE)).hasMessage("status 409 already has a kind");
        assertThatThrownBy(() -> FailureMapper.builder("acme").kind(400, "E1", FailureKind.REJECTED)
                .kind(400, "E1", FailureKind.UNAVAILABLE)).hasMessage("status 400 with E1 already has a kind");
        assertThatThrownBy(() -> FailureMapper.builder("acme").kind(400, "has space", FailureKind.REJECTED))
                .hasMessage("a provider code has only letters, digits and _ . : -, up to 100");
        assertThatThrownBy(() -> FailureMapper.builder("acme").kind(200, "E1", FailureKind.REJECTED))
                .hasMessage("a 200 response is not a failure");
        assertThatThrownBy(() -> FailureMapper.builder("acme").providerCode(SMTP2GO_CODE).providerCode(SMTP2GO_CODE))
                .isInstanceOf(IllegalStateException.class);

        FailureMapper nullReader = FailureMapper.builder("acme").providerCode(new FailureMapper.CodeReader() {
            @Override
            public Optional<String> read(JsonValue body) {
                return null;
            }
        }).build();
        assertThatThrownBy(() -> nullReader.failure(response(400, "{}"), CLOCK))
                .isInstanceOf(NullPointerException.class).hasMessage("CodeReader returned null");
        FailureMapper nullRule = FailureMapper.builder("acme").rule(new FailureMapper.Rule() {
            @Override
            public Optional<FailureKind> kind(HttpResponse response, Optional<JsonValue> body, Optional<String> code) {
                return null;
            }
        }).build();
        assertThatThrownBy(() -> nullRule.failure(response(400, ""), CLOCK))
                .isInstanceOf(NullPointerException.class).hasMessage("Rule returned null");
    }

    @Test
    void mapsARealRateLimitedResponseFromTheEngine() {
        StubHttpServer server = StubHttpServer.start();
        try {
            server.enqueue(StubResponse.status(429).header("Retry-After", "2").header("RateLimit-Remaining", "0")
                    .json("{\"name\":\"rate_limit_exceeded\",\"message\":\"Too many requests\"}"));
            HttpResponse response = HttpEngines.load().execute(HttpRequest.post(server.uri("/emails"))
                    .json("{\"to\":\"" + CANARY + "\"}").build());

            SendFailedException failure = FailureMapper.builder("resend")
                    .providerCode(new FailureMapper.CodeReader() {
                        @Override
                        public Optional<String> read(JsonValue body) {
                            return body.get("name").asString();
                        }
                    }).build()
                    .failure(response, CLOCK);

            assertThat(failure.kind()).isEqualTo(FailureKind.RATE_LIMITED);
            assertThat(failure.retryAfter()).contains(Duration.ofSeconds(2));
            assertThat(failure.providerCode()).contains("rate_limit_exceeded");
            assertThat(failure.getMessage()).isEqualTo("RATE_LIMITED: resend answered 429 (rate_limit_exceeded)");
        } finally {
            server.close();
        }
    }

    private static FailureKind kind(FailureMapper mapper, int status, String body) {
        return mapper.failure(response(status, body), CLOCK).kind();
    }

    private static FailureMapper.Rule always(final FailureKind kind) {
        return new FailureMapper.Rule() {
            @Override
            public Optional<FailureKind> kind(HttpResponse response, Optional<JsonValue> body, Optional<String> code) {
                return Optional.of(kind);
            }
        };
    }

    private static HttpResponse response(int status, String body, String... namesAndValues) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], Collections.singletonList(namesAndValues[i + 1]));
        }
        return HttpResponse.of(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(char c, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            s.append(c);
        }
        return s.toString();
    }
}
