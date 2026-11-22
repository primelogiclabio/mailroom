package io.primelogiclab.mailroom.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attempt;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.MailroomListener;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.TransportRegistry;
import io.primelogiclab.mailroom.spi.ConfigKey;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.TransportConfig;
import io.primelogiclab.mailroom.spi.TransportProvider;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FromPropertiesTest {

    /** "memory" transports record what they send; "unknown-outcome" ones always time out. */
    static final class TestProviders {
        final List<InMemoryTransport> memories = new ArrayList<InMemoryTransport>();
        final List<String> closed = new ArrayList<String>();

        TransportRegistry registry() {
            TransportProvider memory = new TransportProvider() {
                @Override
                public String id() {
                    return "memory";
                }

                @Override
                public Set<ConfigKey> configKeys() {
                    return Collections.singleton(ConfigKey.of("label"));
                }

                @Override
                public MailTransport create(final String name, TransportConfig config) {
                    InMemoryTransport t = InMemoryTransport.named(name);
                    memories.add(t);
                    return new ClosingTransport(t, closed);
                }
            };
            TransportProvider flaky = new TransportProvider() {
                @Override
                public String id() {
                    return "unknown-outcome";
                }

                @Override
                public MailTransport create(String name, TransportConfig config) {
                    return ScriptedTransport.builder(name).thenAlwaysFail(FailureKind.UNKNOWN_OUTCOME).build();
                }
            };
            return TransportRegistry.of(memory, flaky);
        }
    }

    /** Remembers being closed. */
    static final class ClosingTransport implements MailTransport {
        private final InMemoryTransport delegate;
        private final List<String> closed;

        ClosingTransport(InMemoryTransport delegate, List<String> closed) {
            this.delegate = delegate;
            this.closed = closed;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String providerId() {
            return "memory";
        }

        @Override
        public io.primelogiclab.mailroom.spi.TransportCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public SendResult send(Email email, io.primelogiclab.mailroom.spi.SendContext context) {
            return delegate.send(email, context);
        }

        @Override
        public void close() {
            closed.add(delegate.name());
        }
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Email email() {
        return Email.builder().to("player@example.com").subject("Hi").text("Hello").build();
    }

    @Test
    void appliesEveryKey() {
        TestProviders providers = new TestProviders();
        Mailroom mailroom = Mailroom.fromProperties(props(
                "default-from", "Shop <shop@example.com>",
                "transports.internal.type", "unknown-outcome",
                "transports.primary.type", "memory",
                "transports.primary.label", "main",
                "transports.backup.type", "memory",
                "routing.default", "internal, backup ,primary",
                "failover-on-unknown-outcome", "TRUE",
                "retry-backoff", "10ms",
                "max-inline-wait", "1s"), providers.registry());
        try {
            SendResult result = mailroom.send(email());
            assertThat(result.transportName()).as("internal timed out, failover is on, backup is next")
                    .isEqualTo("backup");
            InMemoryTransport backup = providers.memories.get(0).name().equals("backup")
                    ? providers.memories.get(0) : providers.memories.get(1);
            assertThat(backup.assertSentOne("player@example.com").from())
                    .contains(Address.of("Shop", "shop@example.com"));
        } finally {
            mailroom.close();
        }
        assertThat(providers.closed).containsExactlyInAnyOrder("backup", "primary");
    }

    @Test
    void withoutFailoverAnUnknownOutcomeComesBack() {
        Mailroom mailroom = Mailroom.fromProperties(props(
                "default-from", "shop@example.com",
                "transports.a.type", "unknown-outcome",
                "transports.b.type", "memory",
                "routing.default", "a,b"), new TestProviders().registry());
        try {
            assertThatThrownBy(() -> mailroom.send(email())).isInstanceOfSatisfying(SendFailedException.class,
                    e -> assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME));
        } finally {
            mailroom.close();
        }
    }

    @Test
    void reportsEveryProblemAtOnceAndClosesWhatItCreated() {
        TestProviders providers = new TestProviders();
        assertThatThrownBy(() -> Mailroom.fromProperties(props(
                "default_from", "shop@example.com",
                "default-from", "not an address",
                "transports.good.type", "memory",
                "routing.default", "good,missing",
                "failover-on-unknown-outcome", "maybe",
                "retry-backoff", "soon",
                "max-inline-wait", "-1s"), providers.registry()))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.problems()).containsExactly(
                        "mailroom: unknown key 'default_from' (known keys: default-from, failover-on-unknown-outcome,"
                                + " max-inline-wait, retry-backoff, routing.default, transports.<name>.*)",
                        "mailroom: 'default-from' is not a valid address",
                        "mailroom: 'routing.default' names unknown transport 'missing' (configured: [good])",
                        "mailroom: 'failover-on-unknown-outcome' must be true or false",
                        "mailroom: 'retry-backoff' must be a duration such as 250ms, 30s, 5m, 1h or PT30S",
                        "mailroom: 'max-inline-wait' must be a duration such as 250ms, 30s, 5m, 1h or PT30S"));
        assertThat(providers.closed).containsExactly("good");
    }

    @Test
    void transportProblemsComeBackWithTheRest() {
        assertThatThrownBy(() -> Mailroom.fromProperties(props(
                "transports.a.type", "memory",
                "transports.a.lable", "typo",
                "transports.b.type", "sendgird",
                "retry-backoff", "soon"), new TestProviders().registry()))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.problems()).containsExactly(
                        "transport 'a': unknown key 'lable' (known keys: label)",
                        "transport 'b': no provider for type 'sendgird' (found: memory, unknown-outcome)",
                        "mailroom: 'retry-backoff' must be a duration such as 250ms, 30s, 5m, 1h or PT30S"));
    }

    @Test
    void needsAtLeastOneTransport() {
        assertThatThrownBy(() -> Mailroom.fromProperties(props("default-from", "shop@example.com"),
                new TestProviders().registry()))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("mailroom: no transports configured (set transports.<name>.type)");
    }

    @Test
    void discoversProvidersOnTheClasspath() {
        assertThatThrownBy(() -> Mailroom.fromProperties(props("transports.primary.type", "smtp2go")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("no provider for type 'smtp2go' (found: none)");
    }

    @Test
    void takesAListener() {
        final List<String> events = new ArrayList<String>();
        MailroomListener listener = new MailroomListener() {
            @Override
            public void onAttempt(Email email, Attempt attempt) {
                events.add("attempt " + attempt.transportName());
            }

            @Override
            public void onSuccess(Email email, SendResult result) {
                events.add("success " + result.transportName());
            }
        };
        try (Mailroom mailroom = Mailroom.fromProperties(props(
                "default-from", "shop@example.com",
                "transports.primary.type", "memory"), new TestProviders().registry(), listener)) {
            mailroom.send(email());
        }
        assertThat(events).containsExactly("attempt primary", "success primary");
    }

    @Test
    void takesAListenerWithDiscoveredProviders() {
        assertThatThrownBy(() -> Mailroom.fromProperties(props("transports.primary.type", "smtp2go"),
                new MailroomListener() { }))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("no provider for type 'smtp2go' (found: none)");
    }

    @Test
    void rejectsANullListener() {
        assertThatThrownBy(() -> Mailroom.fromProperties(props("transports.primary.type", "memory"),
                new TestProviders().registry(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("listener");
    }
}
