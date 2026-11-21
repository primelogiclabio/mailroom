package io.primelogiclab.mailroom.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CoreVersionTest {

    @Test
    void currentIsTheBuildVersion() {
        assertThat(CoreVersion.current()).matches("\\d+\\.\\d+\\.\\d+.*").doesNotContain("${");
    }

    @ParameterizedTest
    @CsvSource({
        "0.1, 0.1.0-SNAPSHOT, true",
        "0.1, 0.1.0, true",
        "0.1.0, 0.1, true",
        "1.2, 1.10.0, true",
        "1.10, 1.2.0, false",
        "1.2, 1.1.9, false",
        "1.2.3, 1.2.4, true",
        "1.2.3, 1.2.2, false",
        "1, 1.0.0, true",
        "2, 1.99.99, false",
        "0.2, 0.1.0-SNAPSHOT, false",
        "99.0, 0.1.0-SNAPSHOT, false"
    })
    void comparesNumerically(String required, String actual, boolean expected) {
        assertThat(CoreVersion.satisfies(required, actual)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1.2", "one", "1..2", ".1", "1.x"})
    void rejectsTextThatIsNotAVersion(String version) {
        assertThatThrownBy(() -> CoreVersion.satisfies(version, "1.0.0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> CoreVersion.satisfies(null, "1.0.0")).isInstanceOf(IllegalArgumentException.class);
    }
}
