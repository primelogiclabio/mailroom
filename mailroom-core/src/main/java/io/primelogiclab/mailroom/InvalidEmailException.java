package io.primelogiclab.mailroom;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when an email, an address or an attachment breaks a rule. Nothing was sent.
 *
 * <p>{@link #problems()} lists every rule that failed, so one exception reports them all. The problems describe the
 * rule, never the rejected address, subject or body.
 *
 * @since 0.1.0
 */
public non-sealed class InvalidEmailException extends MailroomException {

    private static final long serialVersionUID = 1L;

    /** The rules that failed, in the order they were found. */
    private final List<String> problems;

    /**
     * Creates the exception for one problem.
     *
     * @param problem the rule that failed
     * @since 0.1.0
     */
    public InvalidEmailException(String problem) {
        this(Collections.singletonList(problem));
    }

    /**
     * Creates the exception for several problems.
     *
     * @param problems the rules that failed, in the order they were found; must not be empty
     * @throws IllegalArgumentException if {@code problems} is empty
     * @since 0.1.0
     */
    public InvalidEmailException(List<String> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns every rule that failed.
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
        StringBuilder message = new StringBuilder("Invalid email: ");
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                message.append("; ");
            }
            message.append(problems.get(i));
        }
        return message.toString();
    }
}
