package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Exception messages are logged, so they must never carry addresses, subjects or bodies. */
class InvalidEmailExceptionTest {

    @Test
    void joinsEveryProblemIntoTheMessage() {
        InvalidEmailException e = new InvalidEmailException(Arrays.asList("first", "second"));
        assertThat(e.getMessage()).isEqualTo("Invalid email: first; second");
        assertThat(e.problems()).containsExactly("first", "second");
        assertThat(e).isInstanceOf(MailroomException.class).isInstanceOf(RuntimeException.class);
    }

    @Test
    void problemsCannotBeChanged() {
        InvalidEmailException e = new InvalidEmailException("one");
        assertThatThrownBy(() -> e.problems().add("two")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void needsAtLeastOneProblem() {
        assertThatThrownBy(() -> new InvalidEmailException(Collections.<String>emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildFailuresNeverEchoAddressesSubjectsOrBodies() {
        assertThatThrownBy(() -> Email.builder()
                .to("secret.recipient@example.com")
                .subject("Secret subject\r\nBcc: victim@example.org")
                .text("")
                .header("X-Note", "secret header value\n")
                .metadata("note", "secret metadata\n")
                .build())
                .isInstanceOf(InvalidEmailException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("secret")
                        .doesNotContain("Secret")
                        .doesNotContain("victim")
                        .doesNotContain("@example"));
    }

    @Test
    void addressFailuresNeverEchoTheInput() {
        for (String bad : new String[] {"secret.person@@example.com", "secret person@example.com",
            "secret.person@exa mple.com", "\"secret\"@example.com", "Secret\nName <secret@example.com>"}) {
            assertThatThrownBy(() -> Email.builder().to(bad))
                    .isInstanceOf(InvalidEmailException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContainIgnoringCase("secret"));
        }
    }
}
