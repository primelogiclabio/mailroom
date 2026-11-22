package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.CoreVersion;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.TransportConfig;
import io.primelogiclab.mailroom.spi.TransportProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The transport providers available at runtime, and the code that builds named transports from properties. Provider
 * modules register a {@link TransportProvider} for {@link ServiceLoader}; this class finds them, checks that each one
 * works with this mailroom-core, and creates transports from {@code transports.<name>.*} properties:
 *
 * <pre>
 * transports.primary.type=smtp2go
 * transports.primary.api-key=...
 * transports.backup.type=smtp
 * transports.backup.host=smtp.example.com
 * </pre>
 *
 * <p>Every check happens up front, so a missing provider, an incompatible jar or a typo in a key fails at startup
 * rather than on the first send. {@code Mailroom.fromProperties} builds on this class; most applications use that
 * instead.
 *
 * @since 0.1.0
 */
public final class TransportRegistry {

    private static final String PREFIX = "transports.";
    private static final Pattern TRANSPORT_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final Map<String, TransportProvider> providers;

    private TransportRegistry(Map<String, TransportProvider> providers) {
        this.providers = providers;
    }

    /**
     * Finds every provider registered for {@link ServiceLoader} on the thread's context class loader.
     *
     * @return the registry
     * @throws MailroomException if a provider can't be loaded, needs a newer mailroom-core, or shares its id with
     *                           another provider
     * @since 0.1.0
     */
    public static TransportRegistry discover() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return discover(loader != null ? loader : TransportRegistry.class.getClassLoader());
    }

    /**
     * Finds every provider registered for {@link ServiceLoader} on the given class loader.
     *
     * @param loader the class loader to search
     * @return the registry
     * @throws MailroomException if a provider can't be loaded, needs a newer mailroom-core, or shares its id with
     *                           another provider
     * @since 0.1.0
     */
    public static TransportRegistry discover(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        List<TransportProvider> found = new ArrayList<>();
        Iterator<TransportProvider> it = ServiceLoader.load(TransportProvider.class, loader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                throw new MailroomException("A mailroom TransportProvider could not be loaded: " + e.getMessage(), e);
            }
        }
        return build(found);
    }

    /**
     * Creates a registry from providers you already have, with the same checks as {@link #discover()}.
     *
     * @param providers the providers
     * @return the registry
     * @throws MailroomException if a provider needs a newer mailroom-core or shares its id with another provider
     * @since 0.1.0
     */
    public static TransportRegistry of(TransportProvider... providers) {
        Objects.requireNonNull(providers, "providers");
        return build(Arrays.asList(providers));
    }

    /**
     * Returns the ids of the providers found, such as {@code smtp} and {@code smtp2go}.
     *
     * @return the ids, sorted; the set cannot be changed
     * @since 0.1.0
     */
    public Set<String> providerIds() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /**
     * Returns the provider with this id.
     *
     * @param id the provider id, such as {@code smtp2go}
     * @return the provider, if found
     * @since 0.1.0
     */
    public Optional<TransportProvider> provider(String id) {
        return Optional.ofNullable(providers.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * Creates one transport.
     *
     * @param name       the configured name, such as {@code primary}
     * @param type       the provider id, such as {@code smtp2go}
     * @param properties the transport's properties without {@code type}, such as {@code api-key} to its value
     * @return the transport
     * @throws InvalidConfigException if there is no such provider or the properties don't fit its config keys
     * @since 0.1.0
     */
    public MailTransport create(String name, String type, Map<String, String> properties) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(properties, "properties");
        TransportProvider provider = providers.get(type);
        if (provider == null) {
            throw new InvalidConfigException(unknownType(name, type));
        }
        TransportConfig config = TransportConfig.of(name, properties, provider.configKeys());
        return provider.create(name, config);
    }

    /**
     * Creates every transport configured under {@code transports.<name>.*}. Each name needs a
     * {@code transports.<name>.type}; its other keys go to that provider. Keys outside {@code transports.} are
     * ignored.
     *
     * @param properties all properties, such as a loaded properties file
     * @return the transports by name, sorted by name; the map cannot be changed
     * @throws InvalidConfigException listing every problem across all transports: a missing or unknown type, an
     *                                unknown or missing key, or a malformed {@code transports.} key
     * @since 0.1.0
     */
    public Map<String, MailTransport> createAll(Map<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, String>> blocks = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "property name");
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            String rest = key.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                problems.add("'" + key + "' is not of the form transports.<name>.<key>");
                continue;
            }
            String name = rest.substring(0, dot);
            if (!TRANSPORT_NAME.matcher(name).matches()) {
                problems.add("the transport name '" + name + "' must be letters, digits, '_' or '-'");
                continue;
            }
            Map<String, String> block = blocks.get(name);
            if (block == null) {
                block = new LinkedHashMap<>();
                blocks.put(name, block);
            }
            block.put(rest.substring(dot + 1), entry.getValue());
        }

        Map<String, MailTransport> created = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> block : blocks.entrySet()) {
            String name = block.getKey();
            Map<String, String> settings = block.getValue();
            String type = settings.remove("type");
            if (type == null || type.trim().isEmpty()) {
                problems.add("transport '" + name + "' has no type (set transports." + name + ".type)");
                continue;
            }
            try {
                created.put(name, create(name, type.trim(), settings));
            } catch (InvalidConfigException e) {
                problems.addAll(e.problems());
            }
        }
        if (!problems.isEmpty()) {
            for (MailTransport transport : created.values()) {
                transport.close();
            }
            throw new InvalidConfigException(problems);
        }
        return Collections.unmodifiableMap(created);
    }

    /**
     * Describes the registry, such as {@code TransportRegistry[smtp, smtp2go]}.
     *
     * @return the provider ids
     */
    @Override
    public String toString() {
        return "TransportRegistry" + providers.keySet();
    }

    /** The design's message, such as {@code mailroom-resend 1.3.0 needs mailroom-core ≥ 1.2, found 1.1.0}. */
    static String incompatible(String title, String version, String id, String className, String required,
            String core) {
        String who = title != null
                ? title + (version != null ? " " + version : "")
                : "the provider '" + id + "' (" + className + ")";
        return who + " needs mailroom-core ≥ " + required + ", found " + core;
    }

    private String unknownType(String name, String type) {
        return "transport '" + name + "': no provider for type '" + type + "' (found: "
                + (providers.isEmpty() ? "none" : join(providers.keySet(), ", ")) + ")";
    }

    private static TransportRegistry build(List<TransportProvider> found) {
        String core = CoreVersion.current();
        List<String> problems = new ArrayList<>();
        Map<String, TransportProvider> byId = new TreeMap<>();
        for (TransportProvider provider : found) {
            Objects.requireNonNull(provider, "provider");
            String className = provider.getClass().getName();
            String id = provider.id();
            if (id == null || id.trim().isEmpty()) {
                problems.add("the provider " + className + " has no id");
                continue;
            }
            String required = provider.requiresCore();
            try {
                if (!CoreVersion.satisfies(required, core)) {
                    Package pkg = provider.getClass().getPackage();
                    problems.add(incompatible(pkg == null ? null : pkg.getImplementationTitle(),
                            pkg == null ? null : pkg.getImplementationVersion(), id, className, required, core));
                }
            } catch (IllegalArgumentException e) {
                problems.add("the provider '" + id + "' (" + className + ") declares requiresCore() '" + required
                        + "', which is not a version such as 1.2");
            }
            TransportProvider earlier = byId.put(id, provider);
            if (earlier != null) {
                problems.add("two providers have the id '" + id + "': " + earlier.getClass().getName() + " and "
                        + className);
            }
        }
        if (!problems.isEmpty()) {
            throw new MailroomException(join(problems, "; "));
        }
        return new TransportRegistry(Collections.unmodifiableMap(byId));
    }

    private static String join(Iterable<String> parts, String separator) {
        StringBuilder s = new StringBuilder();
        for (String part : parts) {
            s.append(s.length() == 0 ? "" : separator).append(part);
        }
        return s.toString();
    }
}
