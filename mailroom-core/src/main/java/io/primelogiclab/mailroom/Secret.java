package io.primelogiclab.mailroom;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A credential, such as an API key or password, that never shows up in logs: {@link #toString()} is always
 * {@code Secret[******]}, and errors name where the value comes from, never the value.
 *
 * <pre>{@code
 * Secret.env("SENDGRID_API_KEY")              // read from the environment
 * Secret.of(() -> vault.read("smtp2go/key"))  // read from a vault or secret manager
 * Secret.of(apiKey)                           // a value you already hold
 * }</pre>
 *
 * <p>Environment and supplier secrets are read on every {@link #reveal()}, so a rotated value is picked up without a
 * restart. Two secrets are equal only if they are the same object.
 *
 * @since 0.1.0
 */
public final class Secret {

    private final Supplier<String> source;
    private final String variable;

    private Secret(Supplier<String> source, String variable) {
        this.source = source;
        this.variable = variable;
    }

    /**
     * Wraps a value you already hold.
     *
     * @param value the credential
     * @return the secret
     * @since 0.1.0
     */
    public static Secret of(final String value) {
        Objects.requireNonNull(value, "value");
        return new Secret(new Supplier<String>() {
            @Override
            public String get() {
                return value;
            }
        }, null);
    }

    /**
     * Reads the value from a supplier, such as a vault client, on every {@link #reveal()}.
     *
     * @param supplier where the value comes from; must not return null
     * @return the secret
     * @since 0.1.0
     */
    public static Secret of(Supplier<String> supplier) {
        return new Secret(Objects.requireNonNull(supplier, "supplier"), null);
    }

    /**
     * Reads the value from an environment variable on every {@link #reveal()}.
     *
     * @param variable the variable's name, such as {@code SENDGRID_API_KEY}
     * @return the secret
     * @since 0.1.0
     */
    public static Secret env(String variable) {
        return env(variable, new Function<String, String>() {
            @Override
            public String apply(String name) {
                return System.getenv(name);
            }
        });
    }

    /** For tests: reads the variable through {@code lookup} instead of the real environment. */
    static Secret env(final String variable, final Function<String, String> lookup) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(lookup, "lookup");
        return new Secret(new Supplier<String>() {
            @Override
            public String get() {
                return lookup.apply(variable);
            }
        }, variable);
    }

    /**
     * Returns the value. Call it only where the credential is used, such as when building a request header, and
     * never log the result.
     *
     * @return the credential
     * @throws IllegalStateException if the environment variable is not set or the supplier returns null
     * @since 0.1.0
     */
    public String reveal() {
        String value = source.get();
        if (value == null) {
            throw new IllegalStateException(variable != null
                    ? "The environment variable " + variable + " is not set"
                    : "The secret's supplier returned no value");
        }
        return value;
    }

    /**
     * Returns {@code Secret[******]}, never the value.
     *
     * @return the redacted form
     */
    @Override
    public String toString() {
        return "Secret[******]";
    }
}
