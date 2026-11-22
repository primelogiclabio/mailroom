package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.internal.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * A typed view over one transport's string properties, such as the {@code transports.primary.*} block of a
 * properties file. The same transport then works from a builder, a {@code Map} or Spring properties.
 *
 * <p>{@link #of} checks everything up front against the provider's {@link ConfigKey}s: an unknown key (often a typo
 * such as {@code api_key}) or a missing required key fails at startup with one {@link InvalidConfigException} listing
 * every problem. Errors and {@link #toString()} name keys, never values. Values are trimmed, and a blank value counts
 * as not set.
 *
 * @since 0.1.0
 */
public final class TransportConfig {

    private final String name;
    private final Map<String, String> values;
    private final Map<String, ConfigKey> keys;

    private TransportConfig(String name, Map<String, String> values, Map<String, ConfigKey> keys) {
        this.name = name;
        this.values = values;
        this.keys = keys;
    }

    /**
     * Checks the properties against the declared keys and returns the view.
     *
     * @param transportName the configured transport name, such as {@code primary}; used in error messages
     * @param properties    the transport's properties without the {@code type} key, such as {@code api-key} to its
     *                      value
     * @param keys          the keys the provider accepts
     * @return the config
     * @throws InvalidConfigException listing every unknown key and every missing required key
     * @since 0.1.0
     */
    public static TransportConfig of(String transportName, Map<String, String> properties, Set<ConfigKey> keys) {
        Objects.requireNonNull(transportName, "transportName");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(keys, "keys");
        Map<String, ConfigKey> declared = new LinkedHashMap<>();
        for (ConfigKey key : keys) {
            declared.put(key.name(), key);
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> property : properties.entrySet()) {
            String key = Objects.requireNonNull(property.getKey(), "property name");
            if (!declared.containsKey(key)) {
                problems.add("transport '" + transportName + "': unknown key '" + key + "' (known keys: "
                        + known(declared) + ")");
                continue;
            }
            String value = property.getValue() == null ? "" : property.getValue().trim();
            if (!value.isEmpty()) {
                values.put(key, value);
            }
        }
        for (ConfigKey key : declared.values()) {
            if (key.isRequired() && !key.defaultValue().isPresent() && !values.containsKey(key.name())) {
                problems.add("transport '" + transportName + "': '" + key.name() + "' is required");
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidConfigException(problems);
        }
        return new TransportConfig(transportName, Collections.unmodifiableMap(values),
                Collections.unmodifiableMap(declared));
    }

    /**
     * Returns the configured transport name.
     *
     * @return the name, such as {@code primary}
     * @since 0.1.0
     */
    public String name() {
        return name;
    }

    /**
     * Returns the declared keys.
     *
     * @return the keys, in declaration order; the set cannot be changed
     * @since 0.1.0
     */
    public Set<ConfigKey> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys.values()));
    }

    /**
     * Returns a value, or the key's default when it is not set.
     *
     * @param key the key, such as {@code region}
     * @return the value, if set or defaulted
     * @throws IllegalArgumentException if the provider didn't declare the key
     * @since 0.1.0
     */
    public Optional<String> get(String key) {
        ConfigKey declared = declared(key);
        String value = values.get(key);
        return value != null ? Optional.of(value) : declared.defaultValue();
    }

    /**
     * Returns a value that must be present.
     *
     * @param key the key
     * @return the value
     * @throws InvalidConfigException if it is not set and has no default
     * @throws IllegalArgumentException if the provider didn't declare the key
     * @since 0.1.0
     */
    public String require(String key) {
        Optional<String> value = get(key);
        if (!value.isPresent()) {
            throw new InvalidConfigException("transport '" + name + "': '" + key + "' is required");
        }
        return value.get();
    }

    /**
     * Returns a credential as a {@link Secret}, so it never reaches a log.
     *
     * @param key the key, such as {@code api-key}
     * @return the secret, if set
     * @throws IllegalArgumentException if the provider didn't declare the key
     * @since 0.1.0
     */
    public Optional<Secret> secret(String key) {
        Optional<String> value = get(key);
        return value.isPresent() ? Optional.of(Secret.of(value.get())) : Optional.<Secret>empty();
    }

    /**
     * Returns a duration written as {@code 250ms}, {@code 30s}, {@code 5m}, {@code 1h}, {@code 2d} or ISO-8601
     * ({@code PT30S}).
     *
     * @param key the key, such as {@code read-timeout}
     * @return the duration, if set
     * @throws InvalidConfigException if the value is not such a duration, or is negative
     * @throws IllegalArgumentException if the provider didn't declare the key
     * @since 0.1.0
     */
    public Optional<Duration> duration(String key) {
        Optional<String> value = get(key);
        if (!value.isPresent()) {
            return Optional.empty();
        }
        Duration duration = Durations.parse(value.get());
        if (duration == null) {
            throw new InvalidConfigException("transport '" + name + "': '" + key
                    + "' must be a duration such as 250ms, 30s, 5m, 1h or PT30S");
        }
        return Optional.of(duration);
    }

    /**
     * Returns a whole number.
     *
     * @param key the key, such as {@code port}
     * @return the number, if set
     * @throws InvalidConfigException if the value is not a whole number
     * @throws IllegalArgumentException if the provider didn't declare the key
     * @since 0.1.0
     */
    public OptionalInt integer(String key) {
        Optional<String> value = get(key);
        if (!value.isPresent()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value.get()));
        } catch (NumberFormatException e) {
            throw new InvalidConfigException("transport '" + name + "': '" + key + "' must be a whole number");
        }
    }

    /**
     * Returns {@code true} or {@code false}, in any letter case.
     *
     * @param key the key, such as {@code sandbox-mode}
     * @return the flag, if set
     * @throws InvalidConfigException if the value is neither {@code true} nor {@code false}
     * @throws IllegalArgumentException if the provider didn't declare the key
     * @since 0.1.0
     */
    public Optional<Boolean> bool(String key) {
        Optional<String> value = get(key);
        if (!value.isPresent()) {
            return Optional.empty();
        }
        if ("true".equalsIgnoreCase(value.get())) {
            return Optional.of(Boolean.TRUE);
        }
        if ("false".equalsIgnoreCase(value.get())) {
            return Optional.of(Boolean.FALSE);
        }
        throw new InvalidConfigException("transport '" + name + "': '" + key + "' must be true or false");
    }

    /**
     * Names the transport and the keys that are set, never their values, such as
     * {@code TransportConfig[primary: api-key, region]}.
     *
     * @return a description safe to log
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("TransportConfig[").append(name).append(':');
        String separator = " ";
        for (String key : values.keySet()) {
            s.append(separator).append(key);
            separator = ", ";
        }
        return s.append(']').toString();
    }

    private ConfigKey declared(String key) {
        Objects.requireNonNull(key, "key");
        ConfigKey declared = keys.get(key);
        if (declared == null) {
            throw new IllegalArgumentException("'" + key + "' is not one of this provider's config keys");
        }
        return declared;
    }

    private static String known(Map<String, ConfigKey> declared) {
        if (declared.isEmpty()) {
            return "none";
        }
        StringBuilder s = new StringBuilder();
        for (String key : new TreeSet<>(declared.keySet())) {
            s.append(s.length() == 0 ? "" : ", ").append(key);
        }
        return s.toString();
    }
}
