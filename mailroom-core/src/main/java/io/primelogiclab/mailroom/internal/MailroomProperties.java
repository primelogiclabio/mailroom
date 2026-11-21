package io.primelogiclab.mailroom.internal;

import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.InvalidEmailException;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.MailroomBuilder;
import io.primelogiclab.mailroom.TransportRegistry;
import io.primelogiclab.mailroom.spi.MailTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link Mailroom} from properties, for {@code Mailroom.fromProperties}. Not API: this package may change in
 * any release.
 */
public final class MailroomProperties {

    private static final String DEFAULT_FROM = "default-from";
    private static final String ROUTING_DEFAULT = "routing.default";
    private static final String FAILOVER = "failover-on-unknown-outcome";
    private static final String RETRY_BACKOFF = "retry-backoff";
    private static final String MAX_INLINE_WAIT = "max-inline-wait";
    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            DEFAULT_FROM, ROUTING_DEFAULT, FAILOVER, RETRY_BACKOFF, MAX_INLINE_WAIT));
    private static final String KNOWN_LIST = "default-from, failover-on-unknown-outcome, max-inline-wait, "
            + "retry-backoff, routing.default, transports.<name>.*";

    private MailroomProperties() {
    }

    /**
     * Builds the mailroom, reporting every problem at once.
     *
     * @param properties the properties
     * @param registry   the providers
     * @return the mailroom
     * @throws InvalidConfigException listing every problem
     */
    public static Mailroom create(Map<String, String> properties, TransportRegistry registry) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(registry, "registry");
        List<String> problems = new ArrayList<String>();
        for (String key : properties.keySet()) {
            if (!Objects.requireNonNull(key, "property name").startsWith("transports.") && !KNOWN.contains(key)) {
                problems.add("mailroom: unknown key '" + key + "' (known keys: " + KNOWN_LIST + ")");
            }
        }

        Map<String, MailTransport> transports = Collections.emptyMap();
        boolean transportProblems = false;
        try {
            transports = registry.createAll(properties);
        } catch (InvalidConfigException e) {
            problems.addAll(e.problems());
            transportProblems = true;
        }
        if (transports.isEmpty() && !transportProblems) {
            problems.add("mailroom: no transports configured (set transports.<name>.type)");
        }

        MailroomBuilder builder = Mailroom.builder();
        String from = value(properties, DEFAULT_FROM);
        if (from != null) {
            try {
                builder.defaultFrom(from);
            } catch (InvalidEmailException e) {
                problems.add("mailroom: '" + DEFAULT_FROM + "' is not a valid address");
            }
        }
        String route = value(properties, ROUTING_DEFAULT);
        if (route != null) {
            List<String> names = new ArrayList<String>();
            for (String name : route.split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                names.add(trimmed);
                if (!transportProblems && !transports.containsKey(trimmed)) {
                    problems.add("mailroom: '" + ROUTING_DEFAULT + "' names unknown transport '" + trimmed
                            + "' (configured: " + transports.keySet() + ")");
                }
            }
            if (names.isEmpty()) {
                problems.add("mailroom: '" + ROUTING_DEFAULT + "' must list at least one transport");
            } else {
                builder.defaultRoute(names.toArray(new String[0]));
            }
        }
        String failover = value(properties, FAILOVER);
        if (failover != null) {
            if ("true".equalsIgnoreCase(failover) || "false".equalsIgnoreCase(failover)) {
                builder.failoverOnUnknownOutcome(Boolean.parseBoolean(failover));
            } else {
                problems.add("mailroom: '" + FAILOVER + "' must be true or false");
            }
        }
        Duration backoff = duration(properties, RETRY_BACKOFF, problems);
        if (backoff != null) {
            builder.retryBackoff(backoff);
        }
        Duration inlineWait = duration(properties, MAX_INLINE_WAIT, problems);
        if (inlineWait != null) {
            builder.maxInlineWait(inlineWait);
        }

        if (!problems.isEmpty()) {
            for (MailTransport transport : transports.values()) {
                transport.close();
            }
            throw new InvalidConfigException(problems);
        }
        for (MailTransport transport : transports.values()) {
            builder.transport(transport);
        }
        return builder.build();
    }

    private static String value(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static Duration duration(Map<String, String> properties, String key, List<String> problems) {
        String text = value(properties, key);
        if (text == null) {
            return null;
        }
        Duration duration = Durations.parse(text);
        if (duration == null) {
            problems.add("mailroom: '" + key + "' must be a duration such as 250ms, 30s, 5m, 1h or PT30S");
        }
        return duration;
    }
}
