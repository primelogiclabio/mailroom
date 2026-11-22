package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressTest {

    @Test
    void parsesABareAddress() {
        Address a = Address.parse("  player@example.com ");
        assertThat(a.address()).isEqualTo("player@example.com");
        assertThat(a.displayName()).isEmpty();
        assertThat(a.localPart()).isEqualTo("player");
        assertThat(a.domain()).isEqualTo("example.com");
        assertThat(a.format()).isEqualTo("player@example.com");
    }

    @Test
    void parsesANameAndAddress() {
        Address a = Address.parse("PrimeLogicLab <no-reply@primelogiclab.io>");
        assertThat(a.displayName()).contains("PrimeLogicLab");
        assertThat(a.address()).isEqualTo("no-reply@primelogiclab.io");
        assertThat(a.format()).isEqualTo("PrimeLogicLab <no-reply@primelogiclab.io>");
    }

    @Test
    void parsesAQuotedNameWithEscapes() {
        Address a = Address.parse("\"Smith, Jo \\\"JJ\\\" \\\\ Co\" <jo@example.com>");
        assertThat(a.displayName()).contains("Smith, Jo \"JJ\" \\ Co");
        assertThat(a.format()).isEqualTo("\"Smith, Jo \\\"JJ\\\" \\\\ Co\" <jo@example.com>");
        assertThat(Address.parse(a.format())).isEqualTo(a);
    }

    @Test
    void anEmptyNameBeforeAngleBracketsMeansNoName() {
        assertThat(Address.parse("<jo@example.com>").displayName()).isEmpty();
        assertThat(Address.of("  ", "jo@example.com").displayName()).isEmpty();
        assertThat(Address.of(null, "jo@example.com").displayName()).isEmpty();
    }

    @Test
    void allowsNonAsciiDisplayNames() {
        Address a = Address.of("Zoë Müller", "zoe@example.com");
        assertThat(a.format()).isEqualTo("Zoë Müller <zoe@example.com>");
    }

    @Test
    void quotesNamesWithSpecialsWhenFormatting() {
        assertThat(Address.of("Smith, Jo", "jo@example.com").format()).isEqualTo("\"Smith, Jo\" <jo@example.com>");
        assertThat(Address.of("J. Smith", "jo@example.com").format()).isEqualTo("\"J. Smith\" <jo@example.com>");
    }

    @Test
    void lowerCasesTheDomainButKeepsTheLocalPart() {
        Address a = Address.of("Jo.Smith@Example.COM");
        assertThat(a.address()).isEqualTo("Jo.Smith@example.com");
        assertThat(a).isEqualTo(Address.of("Jo.Smith@example.com"));
        assertThat(a).isNotEqualTo(Address.of("jo.smith@example.com"));
        assertThat(a.hashCode()).isEqualTo(Address.of("Jo.Smith@example.com").hashCode());
    }

    @Test
    void equalityIncludesTheDisplayName() {
        assertThat(Address.of("Jo", "jo@example.com")).isEqualTo(Address.parse("Jo <jo@example.com>"));
        assertThat(Address.of("Jo", "jo@example.com")).isNotEqualTo(Address.of("jo@example.com"));
    }

    @Test
    void acceptsLocalPartSymbolsAndSingleLabelDomains() {
        assertThat(Address.of("a.b+tag!#$%&'*/=?^_`{|}~-x@localhost").domain()).isEqualTo("localhost");
        assertThat(Address.of("x@xn--bcher-kva.example").domain()).isEqualTo("xn--bcher-kva.example");
    }

    @Test
    void toStringIsMasked() {
        Address a = Address.parse("Player One <player@example.com>");
        assertThat(a.toString()).isEqualTo("p***@example.com").doesNotContain("player").doesNotContain("One");
    }

    @Test
    void acceptsTheLongestAllowedParts() {
        String local = repeat('a', 64);
        String label = repeat('b', 63);
        assertThat(Address.of(local + "@" + label + ".com").localPart()).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "player.example.com",
        "player@@example.com",
        "pla yer@example.com",
        "player@exa mple.com",
        "\"quoted\"@example.com",
        "player@[192.0.2.1]",
        ".player@example.com",
        "player.@example.com",
        "pla..yer@example.com",
        "@example.com",
        "player@",
        "player@example..com",
        "player@-example.com",
        "player@example-.com",
        "player@exam_ple.com",
        "plàyer@example.com",
        "player@bücher.example",
        "player@example.com (Player)",
        "Player <player@example.com",
        "Player player@example.com>",
        "\"Player <player@example.com>",
        "Pla\"yer <player@example.com>",
        "\"Pla\"yer\" <player@example.com>",
        "Player\r\nBcc: victim@example.org <player@example.com>",
        "Pla\u0000yer <player@example.com>"
    })
    void rejectsInvalidAddresses(String text) {
        assertThatThrownBy(() -> Address.parse(text)).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void rejectsOverLongParts() {
        assertThatThrownBy(() -> Address.of(repeat('a', 65) + "@example.com"))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("64");
        assertThatThrownBy(() -> Address.of("a@" + repeat('b', 64) + ".com"))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("63");
        String longDomain = repeat('b', 60) + "." + repeat('c', 60) + "." + repeat('d', 60) + "." + repeat('e', 60)
                + "." + repeat('f', 60);
        assertThatThrownBy(() -> Address.of("a@" + longDomain))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("longer than");
    }

    @Test
    void rejectsControlCharactersInADisplayName() {
        assertThatThrownBy(() -> Address.of("Jo\nSmith", "jo@example.com"))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("display name");
    }

    @Test
    void rejectionMessagesNeverRepeatTheInput() {
        assertThatThrownBy(() -> Address.parse("secret.person@@example.com"))
                .isInstanceOf(InvalidEmailException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret.person"));
        assertThatThrownBy(() -> Address.parse("Secret\r\nName <secret.person@example.com>"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("Secret"));
    }

    @Test
    void nullArgumentsThrowNullPointerException() {
        assertThatThrownBy(() -> Address.parse(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Address.of(null)).isInstanceOf(NullPointerException.class);
    }

    static String repeat(char c, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            b.append(c);
        }
        return b.toString();
    }
}
