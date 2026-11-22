package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.SendFailedException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a provider's error response into a {@link SendFailedException}: the {@link FailureKind}, status, the
 * provider's error code, and how long to wait. Build one per transport, adjusting the default table to what that
 * provider's errors mean; immutable and thread-safe.
 *
 * <pre>{@code
 * private static final FailureMapper FAILURES = FailureMapper.builder("smtp2go")
 *     .providerCode(new FailureMapper.CodeReader() {
 *         public Optional<String> read(JsonValue body) {
 *             return body.get("data").get("error_code").asString();
 *         }
 *     })
 *     .kind(400, "E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED", FailureKind.AUTHENTICATION)
 *     .build();
 *
 * HttpResponse response = engine.execute(request);
 * if (!response.isSuccessful()) {
 *     throw FAILURES.failure(response, context.clock());
 * }
 * }</pre>
 *
 * <p>The kind is decided by the first of these that has an answer: the {@linkplain Builder#rule(Rule) rules} in the
 * order added, a {@linkplain Builder#kind(int, String, FailureKind) status and provider code} entry, a
 * {@linkplain Builder#kind(int, FailureKind) status} entry, and the {@linkplain #defaultKind(int) default table}.
 *
 * <p>The exception's message names the provider, the status and the provider code, such as
 * {@code smtp2go answered 400 (E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED)}, and never the body, which often
 * repeats addresses. A provider code is kept only if it is a plain code of letters, digits and {@code _ . : -}, up to
 * 100 characters, so a reader can't pass message text through it.
 *
 * @since 0.1.0
 */
public final class FailureMapper {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final String providerId;
    private final CodeReader codeReader;
    private final List<Rule> rules;
    private final Map<Integer, FailureKind> byStatus;
    private final Map<String, FailureKind> byStatusAndCode;

    private FailureMapper(Builder b) {
        this.providerId = b.providerId;
        this.codeReader = b.codeReader;
        this.rules = Collections.unmodifiableList(new ArrayList<Rule>(b.rules));
        this.byStatus = Collections.unmodifiableMap(new HashMap<Integer, FailureKind>(b.byStatus));
        this.byStatusAndCode = Collections.unmodifiableMap(new HashMap<String, FailureKind>(b.byStatusAndCode));
    }

    /**
     * Starts a mapper for one provider.
     *
     * @param providerId the provider's id, as {@code MailTransport.providerId()} returns it, such as {@code smtp2go};
     *                   it goes into the exception and its message
     * @return a builder
     * @throws IllegalArgumentException if the id is empty or has a control character
     * @since 0.1.0
     */
    public static Builder builder(String providerId) {
        return new Builder(providerId);
    }

    /**
     * Returns the kind for a status when nothing provider-specific applies:
     *
     * <table>
     *   <caption>Default kinds</caption>
     *   <tr><th>Status</th><th>Kind</th><th>Because</th></tr>
     *   <tr><td>400, 413, 422</td><td>{@code INVALID_MESSAGE}</td><td>the content was rejected</td></tr>
     *   <tr><td>401, 403, 407</td><td>{@code AUTHENTICATION}</td><td>credentials, the proxy's for 407</td></tr>
     *   <tr><td>408, 425</td><td>{@code UNAVAILABLE}</td><td>the request wasn't taken in; safe to retry</td></tr>
     *   <tr><td>409</td><td>{@code UNKNOWN_OUTCOME}</td><td>usually an idempotency conflict: the first request may
     *       still deliver</td></tr>
     *   <tr><td>429</td><td>{@code RATE_LIMITED}</td><td></td></tr>
     *   <tr><td>other 4xx</td><td>{@code REJECTED}</td><td>not processed, and won't change on a retry</td></tr>
     *   <tr><td>504</td><td>{@code UNKNOWN_OUTCOME}</td><td>a gateway stopped waiting; the provider behind it may
     *       still send</td></tr>
     *   <tr><td>other 5xx</td><td>{@code UNAVAILABLE}</td><td></td></tr>
     *   <tr><td>1xx, 3xx</td><td>{@code UNAVAILABLE}</td><td>unexpected (redirects are never followed); not
     *       processed</td></tr>
     *   <tr><td>600 to 999</td><td>{@code UNKNOWN_OUTCOME}</td><td>non-standard, so no telling</td></tr>
     * </table>
     *
     * @param status an HTTP status from 100 to 999, not 2xx
     * @return the kind
     * @throws IllegalArgumentException if the status is out of range or 2xx
     * @since 0.1.0
     */
    public static FailureKind defaultKind(int status) {
        checkFailureStatus(status);
        switch (status) {
            case 400:
            case 413:
            case 422:
                return FailureKind.INVALID_MESSAGE;
            case 401:
            case 403:
            case 407:
                return FailureKind.AUTHENTICATION;
            case 408:
            case 425:
                return FailureKind.UNAVAILABLE;
            case 409:
            case 504:
                return FailureKind.UNKNOWN_OUTCOME;
            case 429:
                return FailureKind.RATE_LIMITED;
            default:
                if (status < 400) {
                    return FailureKind.UNAVAILABLE;
                }
                if (status < 500) {
                    return FailureKind.REJECTED;
                }
                return status < 600 ? FailureKind.UNAVAILABLE : FailureKind.UNKNOWN_OUTCOME;
        }
    }

    /**
     * Builds the failure for an error response. The body is read as JSON if it is JSON, for the provider code; a
     * body that isn't (such as a proxy's HTML page) just has no code. The retry-after is the {@code Retry-After}
     * header, or, for {@code RATE_LIMITED} without one, the reset time of an exhausted {@link RateLimit}.
     *
     * @param response the response, not 2xx
     * @param clock    the clock an HTTP-date {@code Retry-After} is measured against, normally
     *                 {@code SendContext.clock()}
     * @return the failure, with kind, status, provider id, provider code and retry-after where known, and no cause;
     *         the transport adds its name with {@link SendFailedException#toBuilder()}
     * @throws IllegalArgumentException if the response is 2xx
     * @since 0.1.0
     */
    public SendFailedException failure(HttpResponse response, Clock clock) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(clock, "clock");
        int status = response.statusCode();
        if (response.isSuccessful()) {
            throw new IllegalArgumentException("a " + status + " response is not a failure");
        }
        Optional<JsonValue> body = response.tryJson();
        Optional<String> code = providerCode(body);
        FailureKind kind = kindOf(response, body, code);

        Optional<Duration> retryAfter = RetryAfter.of(response, clock);
        if (!retryAfter.isPresent() && kind == FailureKind.RATE_LIMITED) {
            Optional<RateLimit> limit = RateLimit.of(response, clock);
            if (limit.isPresent() && limit.get().exhausted()) {
                retryAfter = limit.get().resetIn();
            }
        }

        SendFailedException.Builder failure = SendFailedException.builder(kind, providerId + " answered " + status
                + (code.isPresent() ? " (" + code.get() + ")" : "")).statusCode(status).providerId(providerId);
        if (code.isPresent()) {
            failure.providerCode(code.get());
        }
        if (retryAfter.isPresent()) {
            failure.retryAfter(retryAfter.get());
        }
        return failure.build();
    }

    /**
     * Describes the mapper, such as {@code FailureMapper[smtp2go, 1 rule, 2 overrides]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        int overrides = byStatus.size() + byStatusAndCode.size();
        return "FailureMapper[" + providerId + ", " + rules.size() + (rules.size() == 1 ? " rule, " : " rules, ")
                + overrides + (overrides == 1 ? " override]" : " overrides]");
    }

    private Optional<String> providerCode(Optional<JsonValue> body) {
        if (codeReader == null || !body.isPresent()) {
            return Optional.empty();
        }
        Optional<String> code = Objects.requireNonNull(codeReader.read(body.get()), "CodeReader returned null");
        return code.isPresent() && CODE.matcher(code.get()).matches() ? code : Optional.<String>empty();
    }

    private FailureKind kindOf(HttpResponse response, Optional<JsonValue> body, Optional<String> code) {
        for (Rule rule : rules) {
            Optional<FailureKind> kind = Objects.requireNonNull(rule.kind(response, body, code), "Rule returned null");
            if (kind.isPresent()) {
                return kind.get();
            }
        }
        if (code.isPresent()) {
            FailureKind kind = byStatusAndCode.get(key(response.statusCode(), code.get()));
            if (kind != null) {
                return kind;
            }
        }
        FailureKind kind = byStatus.get(response.statusCode());
        return kind != null ? kind : defaultKind(response.statusCode());
    }

    private static String key(int status, String code) {
        return status + " " + code;
    }

    private static void checkFailureStatus(int status) {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("status must be from 100 to 999: " + status);
        }
        if (status >= 200 && status < 300) {
            throw new IllegalArgumentException("a " + status + " response is not a failure");
        }
    }

    /**
     * Finds a provider's own error code in an error body, such as SMTP2GO's {@code data.error_code} or Graph's
     * {@code error.code}. Must return a code, not message text, and must not throw.
     *
     * @since 0.1.0
     */
    public interface CodeReader {

        /**
         * Reads the code.
         *
         * @param body the error body, when it is JSON
         * @return the code; empty if the body has none
         * @since 0.1.0
         */
        Optional<String> read(JsonValue body);
    }

    /**
     * Decides the kind for responses the tables can't describe, such as a 403 whose message says the sender isn't
     * verified. Must not throw.
     *
     * @since 0.1.0
     */
    public interface Rule {

        /**
         * Decides the kind.
         *
         * @param response     the error response
         * @param body         the body, when it is JSON
         * @param providerCode the provider code, when the {@link CodeReader} found a plain one
         * @return the kind; empty to leave it to the next rule and then the tables
         * @since 0.1.0
         */
        Optional<FailureKind> kind(HttpResponse response, Optional<JsonValue> body, Optional<String> providerCode);
    }

    /**
     * Collects a provider's adjustments to the default table. Start one with {@link FailureMapper#builder(String)}.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final String providerId;
        private CodeReader codeReader;
        private final List<Rule> rules = new ArrayList<Rule>();
        private final Map<Integer, FailureKind> byStatus = new HashMap<Integer, FailureKind>();
        private final Map<String, FailureKind> byStatusAndCode = new HashMap<String, FailureKind>();

        private Builder(String providerId) {
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.trim().isEmpty()) {
                throw new IllegalArgumentException("providerId must not be empty");
            }
            for (int i = 0; i < providerId.length(); i++) {
                if (Character.isISOControl(providerId.charAt(i))) {
                    throw new IllegalArgumentException("providerId must not have control characters");
                }
            }
            this.providerId = providerId;
        }

        /**
         * Sets where this provider's error code is found.
         *
         * @param reader the reader
         * @return this builder
         * @throws IllegalStateException if a reader is already set
         * @since 0.1.0
         */
        public Builder providerCode(CodeReader reader) {
            Objects.requireNonNull(reader, "reader");
            if (codeReader != null) {
                throw new IllegalStateException("the provider code reader is already set");
            }
            this.codeReader = reader;
            return this;
        }

        /**
         * Sets the kind for a status, replacing the default.
         *
         * @param status the HTTP status, from 100 to 999, not 2xx
         * @param kind   the kind
         * @return this builder
         * @throws IllegalArgumentException if the status is out of range or 2xx, or already has a kind
         * @since 0.1.0
         */
        public Builder kind(int status, FailureKind kind) {
            checkFailureStatus(status);
            Objects.requireNonNull(kind, "kind");
            if (byStatus.containsKey(status)) {
                throw new IllegalArgumentException("status " + status + " already has a kind");
            }
            byStatus.put(status, kind);
            return this;
        }

        /**
         * Sets the kind for a status with a given provider code, which comes before the entry for the status alone.
         *
         * @param status       the HTTP status, from 100 to 999, not 2xx
         * @param providerCode the code, exactly as the {@link CodeReader} returns it
         * @param kind         the kind
         * @return this builder
         * @throws IllegalArgumentException if the status is out of range or 2xx, the code isn't a plain code (letters,
         *                                  digits and {@code _ . : -}, up to 100), or the pair already has a kind
         * @since 0.1.0
         */
        public Builder kind(int status, String providerCode, FailureKind kind) {
            checkFailureStatus(status);
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(kind, "kind");
            if (!CODE.matcher(providerCode).matches()) {
                throw new IllegalArgumentException("a provider code has only letters, digits and _ . : -, up to 100");
            }
            String key = key(status, providerCode);
            if (byStatusAndCode.containsKey(key)) {
                throw new IllegalArgumentException("status " + status + " with " + providerCode
                        + " already has a kind");
            }
            byStatusAndCode.put(key, kind);
            return this;
        }

        /**
         * Adds a rule, consulted before the tables and after the rules added earlier.
         *
         * @param rule the rule
         * @return this builder
         * @since 0.1.0
         */
        public Builder rule(Rule rule) {
            rules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /**
         * Builds the mapper.
         *
         * @return the mapper
         * @since 0.1.0
         */
        public FailureMapper build() {
            return new FailureMapper(this);
        }
    }
}
