package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.Masking;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * No configured transport can carry this email, so nothing was sent. Each candidate was skipped for a reason: it lacks
 * a capability the email needs, doesn't understand an essential extension, failed its own precheck (too large, too
 * many recipients), or is unhealthy right now.
 *
 * <p>{@link #skipReasons()} maps each skipped transport to its reason, in route order. The message is safe to log:
 * addresses in the reasons are masked.
 *
 * @since 0.1.0
 */
public non-sealed class NoEligibleTransportException extends MailroomException {

    private static final long serialVersionUID = 1L;

    /** Why each transport was skipped, by transport name, in route order. */
    private final Map<String, String> skipReasons;

    /**
     * Creates the exception.
     *
     * @param skipReasons why each transport was skipped, by transport name, in route order; must not be empty
     * @throws IllegalArgumentException if {@code skipReasons} is empty
     * @since 0.1.0
     */
    public NoEligibleTransportException(Map<String, String> skipReasons) {
        this(masked(skipReasons), true);
    }

    private NoEligibleTransportException(Map<String, String> masked, boolean alreadyMasked) {
        super(message(masked));
        this.skipReasons = Collections.unmodifiableMap(masked);
    }

    /**
     * Returns why each transport was skipped.
     *
     * @return the reasons by transport name, in route order, with addresses masked; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, String> skipReasons() {
        return skipReasons;
    }

    private static Map<String, String> masked(Map<String, String> skipReasons) {
        Objects.requireNonNull(skipReasons, "skipReasons");
        if (skipReasons.isEmpty()) {
            throw new IllegalArgumentException("skipReasons must not be empty");
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : skipReasons.entrySet()) {
            masked.put(Objects.requireNonNull(entry.getKey(), "transport name"),
                    Masking.addressesIn(Objects.requireNonNull(entry.getValue(), "reason")));
        }
        return masked;
    }

    private static String message(Map<String, String> skipReasons) {
        StringBuilder message = new StringBuilder("No transport can carry this email: ");
        String separator = "";
        for (Map.Entry<String, String> entry : skipReasons.entrySet()) {
            message.append(separator).append(entry.getKey()).append(": ").append(entry.getValue());
            separator = "; ";
        }
        return message.toString();
    }
}
