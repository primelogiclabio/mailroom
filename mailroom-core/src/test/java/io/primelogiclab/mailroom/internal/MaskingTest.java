package io.primelogiclab.mailroom.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void masksOneAddress() {
        assertThat(Masking.address("player@example.com")).isEqualTo("p***@example.com");
        assertThat(Masking.address("@example.com")).isEqualTo("***@example.com");
        assertThat(Masking.address("no-at-sign")).isEqualTo("***");
    }

    @Test
    void leavesTextWithoutAddressesAlone() {
        assertThat(Masking.addressesIn("SendGrid answered 503")).isEqualTo("SendGrid answered 503");
        assertThat(Masking.addressesIn("a @ b, @handle, user@")).isEqualTo("a @ b, @handle, user@");
        assertThat(Masking.addressesIn("")).isEmpty();
    }

    @Test
    void masksEveryAddressInText() {
        assertThat(Masking.addressesIn("to <Jo.Smith+x@Example.COM> and b@example.org."))
                .isEqualTo("to <J***@Example.COM> and b***@example.org.");
    }

    @Test
    void handlesReplacementSpecialCharacters() {
        assertThat(Masking.addressesIn("$weird@example.com")).isEqualTo("$***@example.com");
        assertThat(Masking.addressesIn("x\\y@example.com")).isEqualTo("x\\y***@example.com");
    }
}
