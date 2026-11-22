package io.primelogiclab.mailroom.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One configuration key a {@link TransportProvider} accepts, such as {@code api-key} or {@code region}. Providers
 * list their keys in {@link TransportProvider#configKeys()}, so a typo fails at startup and tools can show hints.
 *
 * <pre>{@code
 * ConfigKey.of("api-key").required().secret().description("SMTP2GO API key with /email/send enabled")
 * ConfigKey.of("region").withDefault("global").description("global, us, eu or au")
 * }</pre>
 *
 * <p>Keys are immutable: each method returns a changed copy. Names are lower-case kebab-case, such as
 * {@code tenant-id}, with dots allowed for groups. Two keys with the same name are equal.
 *
 * @since 0.1.0
 */
public final class ConfigKey {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+([.-][a-z0-9]+)*");

    private final String name;
    private final String description;
    private final boolean required;
    private final boolean secret;
    private final String defaultValue;

    private ConfigKey(String name, String description, boolean required, boolean secret, String defaultValue) {
        this.name = name;
        this.description = description;
        this.required = required;
        this.secret = secret;
        this.defaultValue = defaultValue;
    }

    /**
     * Creates an optional, non-secret key without a default.
     *
     * @param name the key, such as {@code api-key}
     * @return the key
     * @throws IllegalArgumentException if the name is not lower-case kebab-case
     * @since 0.1.0
     */
    public static ConfigKey of(String name) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A config key must be lower-case kebab-case, such as api-key");
        }
        return new ConfigKey(name, null, false, false, null);
    }

    /**
     * Returns a copy that must be set. A key with a default is never missing.
     *
     * @return the changed copy
     * @since 0.1.0
     */
    public ConfigKey required() {
        return new ConfigKey(name, description, true, secret, defaultValue);
    }

    /**
     * Returns a copy marked as a credential: read it with {@link TransportConfig#secret(String)}, and tools must not
     * show its value.
     *
     * @return the changed copy
     * @since 0.1.0
     */
    public ConfigKey secret() {
        return new ConfigKey(name, description, required, true, defaultValue);
    }

    /**
     * Returns a copy with a description for documentation and tools.
     *
     * @param description what the key is for
     * @return the changed copy
     * @since 0.1.0
     */
    public ConfigKey description(String description) {
        return new ConfigKey(name, Objects.requireNonNull(description, "description"), required, secret,
                defaultValue);
    }

    /**
     * Returns a copy with a value used when the key is not set.
     *
     * @param defaultValue the default
     * @return the changed copy
     * @since 0.1.0
     */
    public ConfigKey withDefault(String defaultValue) {
        return new ConfigKey(name, description, required, secret,
                Objects.requireNonNull(defaultValue, "defaultValue"));
    }

    /**
     * Returns the key's name.
     *
     * @return the name, such as {@code api-key}
     * @since 0.1.0
     */
    public String name() {
        return name;
    }

    /**
     * Returns the description.
     *
     * @return the description, if set
     * @since 0.1.0
     */
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    /**
     * Tells whether the key must be set.
     *
     * @return true if required
     * @since 0.1.0
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * Tells whether the key holds a credential.
     *
     * @return true if secret
     * @since 0.1.0
     */
    public boolean isSecret() {
        return secret;
    }

    /**
     * Returns the value used when the key is not set.
     *
     * @return the default, if any
     * @since 0.1.0
     */
    public Optional<String> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }

    /**
     * Two keys are equal when their names are equal.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ConfigKey && name.equals(((ConfigKey) other).name));
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * Returns the key's name.
     *
     * @return the name
     */
    @Override
    public String toString() {
        return name;
    }
}
