package io.primelogiclab.mailroom.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.BatchItemResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.ConfigKey;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.spi.TransportConfig;
import io.primelogiclab.mailroom.spi.TransportProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The CORE-4 done-when: a complete provider written against the public model and the spi package alone. It lives
 * outside mailroom's own packages, so it can't use anything package-private or internal.
 */
class FakeProviderTest {

    /** A provider as a provider module would write it. */
    static final class FakeProvider implements TransportProvider {
        static final ConfigKey API_KEY = ConfigKey.of("api-key").required().secret().description("Fake API key");
        static final ConfigKey REGION = ConfigKey.of("region").withDefault("global").description("global or eu");
        static final ConfigKey MAX_SIZE = ConfigKey.of("max-size").withDefault("1000");

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public MailTransport create(String name, TransportConfig config) {
            return new FakeTransport(name, config.secret("api-key").get(), config.require("region"),
                    config.integer("max-size").getAsInt());
        }

        @Override
        public Set<ConfigKey> configKeys() {
            return new LinkedHashSet<ConfigKey>(Arrays.asList(API_KEY, REGION, MAX_SIZE));
        }
    }

    /** A transport that "sends" by counting, and fails for one recipient domain. */
    static final class FakeTransport implements MailTransport {
        private final String name;
        private final Secret apiKey;
        private final String region;
        private final int maxSize;
        final AtomicInteger sent = new AtomicInteger();

        FakeTransport(String name, Secret apiKey, String region, int maxSize) {
            this.name = name;
            this.apiKey = apiKey;
            this.region = region;
            this.maxSize = maxSize;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String providerId() {
            return "fake";
        }

        @Override
        public TransportCapabilities capabilities() {
            return TransportCapabilities.builder().supports(Capability.TEXT, Capability.HTML, Capability.MESSAGE_ID)
                    .maxMessageSize(maxSize).build();
        }

        @Override
        public Precheck precheck(Email email) {
            int size = email.text().map(String::length).orElse(0) + email.html().map(String::length).orElse(0);
            return size > maxSize ? Precheck.reject("body of " + size + " chars is over the " + maxSize + " limit")
                    : Precheck.ok();
        }

        @Override
        public SendResult send(Email email, SendContext context) {
            if (apiKey.reveal().isEmpty()) {
                throw SendFailedException.builder(FailureKind.AUTHENTICATION, "empty key").transportName(name).build();
            }
            if (email.to().get(0).domain().equals("down.example")) {
                throw SendFailedException.builder(FailureKind.UNAVAILABLE, "fake " + region + " answered 503")
                        .transportName(name).providerId("fake").statusCode(503).build();
            }
            return SendResult.builder().transportName(name).providerId("fake")
                    .providerMessageId("fake-" + sent.incrementAndGet()).acceptedAt(context.clock().instant()).build();
        }
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);

    private static TransportConfig config(Map<String, String> properties) {
        return TransportConfig.of("primary", properties, new FakeProvider().configKeys());
    }

    private static MailTransport transport() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put("api-key", "k-123");
        return new FakeProvider().create("primary", config(properties));
    }

    private static Email to(String address) {
        return Email.builder().to(address).subject("Hi").text("Hello").build();
    }

    private static SendContext context() {
        return SendContext.builder().clock(CLOCK).build();
    }

    @Test
    void theProviderBuildsATransportFromCheckedConfig() {
        MailTransport t = transport();
        assertThat(t.name()).isEqualTo("primary");
        assertThat(t.providerId()).isEqualTo("fake");
        assertThat(t.capabilities().supports(Capability.HTML)).isTrue();
        assertThat(t.capabilities().maxMessageSize()).hasValue(1000);
        assertThat(new FakeProvider().requiresCore()).isEqualTo("0.1");
    }

    @Test
    void sendReturnsAResult() {
        SendResult r = transport().send(to("player@example.com"), context());
        assertThat(r.transportName()).isEqualTo("primary");
        assertThat(r.providerMessageId()).contains("fake-1");
        assertThat(r.acceptedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void sendThrowsAClassifiedFailure() {
        assertThatThrownBy(() -> transport().send(to("x@down.example"), context()))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE);
                    assertThat(e.statusCode()).hasValue(503);
                });
    }

    @Test
    void precheckDefaultsToOkAndCanBeOverridden() {
        MailTransport plain = new MailTransport() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public String providerId() {
                return "fake";
            }

            @Override
            public TransportCapabilities capabilities() {
                return TransportCapabilities.builder().build();
            }

            @Override
            public SendResult send(Email email, SendContext context) {
                throw new UnsupportedOperationException();
            }
        };
        assertThat(plain.precheck(to("a@example.com"))).isEqualTo(Precheck.ok());
        plain.close();

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            big.append('x');
        }
        Precheck check = transport().precheck(Email.builder().to("a@example.com").subject("s").text(big.toString())
                .build());
        assertThat(check.passed()).isFalse();
        assertThat(check.reason()).contains("body of 1001 chars is over the 1000 limit");
    }

    @Test
    void sendAsyncRunsOnTheContextExecutor() throws Exception {
        final AtomicInteger executed = new AtomicInteger();
        Executor counting = new Executor() {
            @Override
            public void execute(Runnable command) {
                executed.incrementAndGet();
                command.run();
            }
        };
        SendContext ctx = SendContext.builder().clock(CLOCK).executor(counting).build();
        SendResult r = transport().sendAsync(to("a@example.com"), ctx).toCompletableFuture().get();
        assertThat(r.providerMessageId()).contains("fake-1");
        assertThat(executed.get()).isEqualTo(1);
    }

    @Test
    void sendAsyncCompletesExceptionallyWithTheFailure() {
        assertThatThrownBy(() -> transport().sendAsync(to("x@down.example"), context()).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SendFailedException.class);
    }

    @Test
    void sendBatchRecordsEachOutcomeWithoutStopping() {
        List<BatchItemResult> results = transport().sendBatch(
                Arrays.asList(to("a@example.com"), to("x@down.example"), to("b@example.com")), context());
        assertThat(results).hasSize(3);
        assertThat(results.get(0).succeeded()).isTrue();
        assertThat(results.get(1).succeeded()).isFalse();
        assertThat(results.get(1).failure().get().kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(results.get(2).result().get().providerMessageId()).contains("fake-2");
        assertThat(transport().sendBatch(Collections.<Email>emptyList(), context())).isEmpty();
    }

    @Test
    void theSecretNeverShowsInTheConfigOrTransport() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put("api-key", "super-secret-key");
        TransportConfig config = config(properties);
        assertThat(config.toString()).doesNotContain("super-secret-key");
        assertThat(config.secret("api-key").get().toString()).isEqualTo("Secret[******]");
    }

    @Test
    void aConfigTypoFailsAtStartup() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put("api_key", "k-123");
        assertThatThrownBy(() -> config(properties))
                .hasMessageContaining("unknown key 'api_key' (known keys: api-key, max-size, region)")
                .hasMessageContaining("'api-key' is required")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("k-123"));
    }

    @Test
    void durationsAreAvailableToProviders() {
        Set<ConfigKey> keys = Collections.singleton(ConfigKey.of("read-timeout").withDefault("30s"));
        assertThat(TransportConfig.of("t", new HashMap<String, String>(), keys).duration("read-timeout"))
                .contains(Duration.ofSeconds(30));
    }
}
