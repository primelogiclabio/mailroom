package io.primelogiclab.mailroom;

import java.util.Collections;
import java.util.List;

/**
 * The mailroom configuration is wrong: an unknown key (often a typo such as {@code api_key}), a missing required key,
 * or a value that can't be read. Thrown at startup, so a mistake fails before the first email instead of as a 401 in
 * production.
 *
 * <p>{@link #problems()} lists every problem found. They name the transport and the key, never a value, so secrets
 * stay out of logs.
 *
 * @since 0.1.0
 */
public non-sealed class InvalidConfigException extends MailroomException {

    private static final long serialVersionUID = 1L;

    /** The problems found, in order. */
    private final List<String> problems;

    /**
     * Creates the exception for one problem.
     *
     * @param problem what is wrong, without the value
     * @since 0.1.0
     */
    public InvalidConfigException(String problem) {
        this(Collections.singletonList(problem));
    }

    /**
     * Creates the exception for several problems.
     *
     * @param problems what is wrong, without values; must not be empty
     * @throws IllegalArgumentException if {@code problems} is empty
     * @since 0.1.0
     */
    public InvalidConfigException(List<String> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns every problem found.
     *
     * @return the problems, never empty; the list cannot be changed
     * @since 0.1.0
     */
    public List<String> problems() {
        return problems;
    }

    private static String message(List<String> problems) {
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("problems must not be empty");
        }
        StringBuilder message = new StringBuilder("Invalid mailroom configuration: ");
        for (int i = 0; i < problems.size(); i++) {
            message.append(i == 0 ? "" : "; ").append(problems.get(i));
        }
        return message.toString();
    }
}
