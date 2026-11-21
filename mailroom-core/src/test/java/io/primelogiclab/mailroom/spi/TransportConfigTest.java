package io.primelogiclab.mailroom.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.InvalidConfigException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransportConfigTest {

    private static final Set<ConfigKey> KEYS = new LinkedHashSet<ConfigKey>(Arrays.asList(
            ConfigKey.of("api-key").required().secret(),
            ConfigKey.of("region").withDefault("global"),
            ConfigKey.of("port"),
            ConfigKey.of("sandbox-mode"),
            ConfigKey.of("read-timeout")));

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static TransportConfig config(String... keyValues) {
        return TransportConfig.of("primary", props(keyValues), KEYS);
    }

    @Test
    void readsValuesAndDefaults() {
        TransportConfig c = config("api-key", " k-1 ", "port", "2525");
        assertThat(c.name()).isEqualTo("primary");
        assertThat(c.require("api-key")).isEqualTo("k-1");
        assertThat(c.get("region")).contains("global");
        assertThat(c.get("port")).contains("2525");
        assertThat(c.get("sandbox-mode")).isEmpty();
        assertThat(c.keys()).extracting(ConfigKey::name)
                .containsExactly("api-key", "region", "port", "sandbox-mode", "read-timeout");
    }

    @Test
    void blankValuesCountAsNotSet() {
        assertThatThrownBy(() -> config("api-key", "   ")).isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("'api-key' is required");
        assertThat(config("api-key", "k", "port", "").get("port")).isEmpty();
    }

    @Test
    void reportsUnknownAndMissingKeysTogether() {
        assertThatThrownBy(() -> config("api_key", "k-1", "regoin", "eu"))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.problems()).containsExactly(
                        "transport 'primary': unknown key 'api_key' (known keys: api-key, port, read-timeout, region, sandbox-mode)",
                        "transport 'primary': unknown key 'regoin' (known keys: api-key, port, read-timeout, region, sandbox-mode)",
                        "transport 'primary': 'api-key' is required"));
    }

    @Test
    void neverEchoesValuesInErrorsOrToString() {
        assertThatThrownBy(() -> config("api_key", "secret-value-1"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-value-1"));
        TransportConfig c = config("api-key", "secret-value-2", "port", "not-a-port");
        assertThat(c.toString()).isEqualTo("TransportConfig[primary: api-key, port]").doesNotContain("secret-value-2");
        assertThatThrownBy(() -> c.integer("port"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("not-a-port"));
        assertThat(c.secret("api-key").get().reveal()).isEqualTo("secret-value-2");
    }

    @Test
    void parsesIntegersAndBooleans() {
        TransportConfig c = config("api-key", "k", "port", "587", "sandbox-mode", "TRUE");
        assertThat(c.integer("port")).hasValue(587);
        assertThat(c.bool("sandbox-mode")).contains(true);
        assertThat(config("api-key", "k", "sandbox-mode", "false").bool("sandbox-mode")).contains(false);
        assertThat(config("api-key", "k").integer("port")).isEmpty();
        assertThat(config("api-key", "k").bool("sandbox-mode")).isEmpty();
        assertThatThrownBy(() -> config("api-key", "k", "sandbox-mode", "yes").bool("sandbox-mode"))
                .isInstanceOf(InvalidConfigException.class).hasMessage(
                        "Invalid mailroom configuration: transport 'primary': 'sandbox-mode' must be true or false");
        assertThatThrownBy(() -> config("api-key", "k", "port", "25.5").integer("port"))
                .isInstanceOf(InvalidConfigException.class).hasMessageContaining("must be a whole number");
    }

    @Test
    void parsesDurations() {
        assertThat(duration("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(duration("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(duration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(duration("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(duration("2d")).isEqualTo(Duration.ofDays(2));
        assertThat(duration("PT1M30S")).isEqualTo(Duration.ofSeconds(90));
        assertThat(duration("pt10s")).isEqualTo(Duration.ofSeconds(10));
        assertThat(duration("30S")).isEqualTo(Duration.ofSeconds(30));
        assertThat(config("api-key", "k").duration("read-timeout")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "s", "1.5s", "-5s", "PT-5S", "five seconds", "30 s", "P", "99999999999999999999s"})
    void rejectsMalformedDurations(String value) {
        assertThatThrownBy(() -> config("api-key", "k", "read-timeout", value).duration("read-timeout"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("'read-timeout' must be a duration");
    }

    @Test
    void undeclaredKeysAreAProviderBug() {
        TransportConfig c = config("api-key", "k");
        assertThatThrownBy(() -> c.get("password")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.require("password")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireFailsForAnUnsetOptionalKey() {
        assertThatThrownBy(() -> config("api-key", "k").require("port")).isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("'port' is required");
    }

    @Test
    void aProviderWithoutKeysAcceptsNoProperties() {
        assertThat(TransportConfig.of("t", props(), Collections.<ConfigKey>emptySet()).keys()).isEmpty();
        assertThatThrownBy(() -> TransportConfig.of("t", props("host", "x"), Collections.<ConfigKey>emptySet()))
                .hasMessageContaining("unknown key 'host' (known keys: none)");
    }

    private static Duration duration(String value) {
        return config("api-key", "k", "read-timeout", value).duration("read-timeout").get();
    }
}
