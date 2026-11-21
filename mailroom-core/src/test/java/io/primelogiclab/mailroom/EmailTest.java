package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One nested class per row of the design's Email table: each accepts a valid value and rejects a bad one. */
class EmailTest {

    /** The smallest valid email; tests change one field at a time. */
    static Email.Builder minimal() {
        return Email.builder().to("player@example.com").subject("Hello").text("Hi there");
    }

    static List<String> problemsOf(Email.Builder builder) {
        try {
            builder.build();
        } catch (InvalidEmailException e) {
            return e.problems();
        }
        throw new AssertionError("expected InvalidEmailException");
    }

    @Test
    void buildsTheDesignExample() {
        byte[] pdf = {'%', 'P', 'D', 'F'};
        Email email = Email.builder()
                .from("PrimeLogicLab <no-reply@primelogiclab.io>")
                .to("player@example.com")
                .subject("Your tee time is confirmed")
                .text("text body")
                .html("<p>html body</p>")
                .attach(Attachment.of("scorecard.pdf", "application/pdf", pdf))
                .tag("booking")
                .metadata("bookingId", "B-1042")
                .idempotencyKey("booking-confirmed/B-1042")
                .build();
        assertThat(email.from()).contains(Address.parse("PrimeLogicLab <no-reply@primelogiclab.io>"));
        assertThat(email.to()).containsExactly(Address.of("player@example.com"));
        assertThat(email.subject()).contains("Your tee time is confirmed");
        assertThat(email.text()).contains("text body");
        assertThat(email.html()).contains("<p>html body</p>");
        assertThat(email.attachments()).hasSize(1);
        assertThat(email.tags()).containsExactly("booking");
        assertThat(email.metadata()).containsEntry("bookingId", "B-1042");
        assertThat(email.idempotencyKey()).contains("booking-confirmed/B-1042");
        assertThat(email.sendAt()).isEmpty();
    }

    @Nested
    class From {
        @Test
        void isOptionalBecauseMailroomFillsInTheDefault() {
            assertThat(minimal().build().from()).isEmpty();
        }

        @Test
        void acceptsAnAddressOrText() {
            Address sender = Address.of("Shop", "shop@example.com");
            assertThat(minimal().from(sender).build().from()).contains(sender);
            assertThat(minimal().from("Shop <shop@example.com>").build().from()).contains(sender);
        }

        @Test
        void rejectsAnInvalidAddressWhenAdded() {
            assertThatThrownBy(() -> minimal().from("not an address")).isInstanceOf(InvalidEmailException.class);
        }
    }

    @Nested
    class Recipients {
        @Test
        void acceptsToCcOrBccAlone() {
            assertThat(Email.builder().subject("s").text("t").to("a@example.com").build().recipients()).hasSize(1);
            assertThat(Email.builder().subject("s").text("t").cc("a@example.com").build().to()).isEmpty();
            assertThat(Email.builder().subject("s").text("t").bcc("a@example.com").build().bcc()).hasSize(1);
        }

        @Test
        void requiresAtLeastOne() {
            assertThat(problemsOf(Email.builder().subject("s").text("t")))
                    .containsExactly("at least one recipient (to, cc or bcc) is required");
        }

        @Test
        void recipientsListsToThenCcThenBcc() {
            Email email = minimal().cc(Address.of("c@example.com")).bcc(Arrays.asList(Address.of("b@example.com")))
                    .to(Arrays.asList(Address.of("t2@example.com"))).build();
            assertThat(email.recipients()).extracting(Address::address)
                    .containsExactly("player@example.com", "t2@example.com", "c@example.com", "b@example.com");
        }

        @Test
        void keepsReplyToSeparately() {
            Email email = minimal().replyTo("support@example.com").replyTo(Address.of("help@example.com")).build();
            assertThat(email.replyTo()).extracting(Address::address)
                    .containsExactly("support@example.com", "help@example.com");
            assertThat(email.recipients()).hasSize(1);
        }

        @Test
        void rejectsAnInvalidAddressWhenAdded() {
            assertThatThrownBy(() -> minimal().to("bad@@example.com")).isInstanceOf(InvalidEmailException.class);
            assertThatThrownBy(() -> minimal().cc("bad")).isInstanceOf(InvalidEmailException.class);
            assertThatThrownBy(() -> minimal().bcc("a@b..c")).isInstanceOf(InvalidEmailException.class);
            assertThatThrownBy(() -> minimal().replyTo("x y@z")).isInstanceOf(InvalidEmailException.class);
        }
    }

    @Nested
    class Subject {
        @Test
        void isRequired() {
            assertThat(problemsOf(Email.builder().to("a@example.com").text("t"))).containsExactly("a subject is required");
            assertThat(problemsOf(minimal().subject("   "))).containsExactly("a subject is required");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Hi\r\nBcc: victim@example.org", "Hi\nthere", "Hi\u0007"})
        void rejectsLineBreaksAndControls(String subject) {
            assertThat(problemsOf(minimal().subject(subject)))
                    .containsExactly("the subject must not contain line breaks or other control characters");
        }

        @Test
        void allowsAnyLanguage() {
            assertThat(minimal().subject("Réservation confirmée ✓").build().subject()).contains("Réservation confirmée ✓");
        }
    }

    @Nested
    class Bodies {
        @Test
        void acceptsTextHtmlOrBoth() {
            assertThat(Email.builder().to("a@example.com").subject("s").text("t").build().html()).isEmpty();
            assertThat(Email.builder().to("a@example.com").subject("s").html("<p>h</p>").build().text()).isEmpty();
            assertThat(minimal().html("<p>h</p>").build().html()).contains("<p>h</p>");
        }

        @Test
        void requiresAtLeastOne() {
            assertThat(problemsOf(Email.builder().to("a@example.com").subject("s")))
                    .containsExactly("a text body, an HTML body or both are required");
        }

        @Test
        void rejectsEmptyBodies() {
            assertThat(problemsOf(minimal().text(""))).containsExactly("the text body must not be empty");
            assertThat(problemsOf(minimal().html(""))).containsExactly("the HTML body must not be empty");
        }
    }

    @Nested
    class Attachments {
        @Test
        void acceptsRegularAndInlineParts() {
            Email email = minimal()
                    .attach(Attachment.of("a.pdf", "application/pdf", new byte[] {1}))
                    .attach(Attachment.inline("logo", "image/png", new byte[] {2}))
                    .attach(Attachment.inline("banner", "image/png", new byte[] {3}))
                    .build();
            assertThat(email.attachments()).hasSize(3);
        }

        @Test
        void rejectsTwoInlinePartsWithTheSameContentId() {
            assertThat(problemsOf(minimal()
                    .attach(Attachment.inline("logo", "image/png", new byte[] {1}))
                    .attach(Attachment.inline("logo", "image/gif", new byte[] {2}))))
                    .containsExactly("the content id 'logo' is used by more than one inline attachment");
        }
    }

    @Nested
    class Headers {
        @Test
        void acceptsCustomHeadersInOrder() {
            Email email = minimal().header("List-Unsubscribe", "<mailto:unsub@example.com>")
                    .header("X-Campaign", "spring\tsale").header("In-Reply-To", "<abc@example.com>")
                    .header("References", "<abc@example.com>").build();
            assertThat(email.headers()).containsExactly(
                    entry("List-Unsubscribe", "<mailto:unsub@example.com>"), entry("X-Campaign", "spring\tsale"),
                    entry("In-Reply-To", "<abc@example.com>"), entry("References", "<abc@example.com>"));
        }

        @Test
        void aLaterValueForTheSameNameReplacesTheEarlierOne() {
            Email email = minimal().header("X-Tag", "one").header("x-tag", "two").build();
            assertThat(email.headers()).containsExactly(entry("x-tag", "two"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"From", "sender", "Reply-To", "TO", "Cc", "Bcc", "Subject", "Date", "Message-ID",
            "MIME-Version", "Content-Type", "Content-Transfer-Encoding", "Content-ID", "Content-Disposition",
            "Return-Path"})
        void rejectsReservedNames(String name) {
            assertThat(problemsOf(minimal().header(name, "x")))
                    .containsExactly("the header '" + name + "' is set by the email itself; use its own field");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "X Bad", "X:Bad", "X-Bäd", "X-Bad\r\n"})
        void rejectsInvalidNames(String name) {
            assertThat(problemsOf(minimal().header(name, "x")))
                    .containsExactly("a header name must be printable ASCII without ':' or spaces");
        }

        @ParameterizedTest
        @ValueSource(strings = {"a\r\nBcc: victim@example.org", "a\nb", "a\rb", "a\u0000b"})
        void rejectsLineBreaksAndControlsInValues(String value) {
            assertThat(problemsOf(minimal().header("X-Thing", value)))
                    .containsExactly("the value of header 'X-Thing' must not contain line breaks or other control characters");
        }
    }

    @Nested
    class Tags {
        @Test
        void acceptsTokensAndCollapsesDuplicates() {
            Email email = minimal().tag("booking", "auth_reset").tag("booking", "v-2").build();
            assertThat(email.tags()).containsExactly("booking", "auth_reset", "v-2");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "two words", "dot.ted", "émoji", "new\nline"})
        void rejectsNonTokens(String tag) {
            assertThat(problemsOf(minimal().tag("ok", tag)))
                    .containsExactly("tag 2 must be 1 to 64 ASCII letters, digits, '_' or '-'");
        }

        @Test
        void rejectsTagsLongerThan64() {
            assertThat(problemsOf(minimal().tag(AddressTest.repeat('t', 65)))).hasSize(1);
            assertThat(minimal().tag(AddressTest.repeat('t', 64)).build().tags()).hasSize(1);
        }
    }

    @Nested
    class Metadata {
        @Test
        void acceptsTokenKeysAndReplacesValues() {
            Email email = minimal().metadata("bookingId", "B-1042").metadata("bookingId", "B-1043")
                    .metadata("source", "web app").build();
            assertThat(email.metadata()).containsExactly(entry("bookingId", "B-1043"), entry("source", "web app"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "booking id", "booking.id", "bookingÏd"})
        void rejectsNonTokenKeys(String key) {
            assertThat(problemsOf(minimal().metadata(key, "v")))
                    .containsExactly("metadata key 1 must be 1 to 64 ASCII letters, digits, '_' or '-'");
        }

        @Test
        void rejectsControlCharactersInValues() {
            assertThat(problemsOf(minimal().metadata("note", "a\r\nb")))
                    .containsExactly("the metadata value for 'note' must not contain line breaks or other control characters");
        }
    }

    @Nested
    class SendAt {
        @Test
        void isOptional() {
            Instant at = Instant.parse("2026-12-01T09:00:00Z");
            assertThat(minimal().sendAt(at).build().sendAt()).contains(at);
            assertThat(minimal().build().sendAt()).isEmpty();
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> minimal().sendAt(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("sendAt");
        }
    }

    @Nested
    class IdempotencyKey {
        @Test
        void acceptsVisibleAsciiUpTo256() {
            assertThat(minimal().idempotencyKey("booking-confirmed/B-1042").build().idempotencyKey())
                    .contains("booking-confirmed/B-1042");
            assertThat(minimal().idempotencyKey(AddressTest.repeat('k', 256)).build().idempotencyKey()).isPresent();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "has space", "tab\there", "ünicode", "line\nbreak"})
        void rejectsOtherKeys(String key) {
            assertThat(problemsOf(minimal().idempotencyKey(key)))
                    .containsExactly("the idempotency key must be 1 to 256 visible ASCII characters (no spaces)");
        }

        @Test
        void rejectsKeysLongerThan256() {
            assertThat(problemsOf(minimal().idempotencyKey(AddressTest.repeat('k', 257)))).hasSize(1);
        }
    }

    @Test
    void reportsEveryProblemAtOnce() {
        List<String> problems = problemsOf(Email.builder().header("Subject", "x").tag("bad tag"));
        assertThat(problems).containsExactly(
                "at least one recipient (to, cc or bcc) is required",
                "a subject is required",
                "a text body, an HTML body or both are required",
                "the header 'Subject' is set by the email itself; use its own field",
                "tag 1 must be 1 to 64 ASCII letters, digits, '_' or '-'");
    }

    @Test
    void isImmutable() {
        Email.Builder builder = minimal().tag("one");
        Email email = builder.build();
        builder.to("other@example.com").tag("two").subject("Changed");
        assertThat(email.to()).hasSize(1);
        assertThat(email.tags()).containsExactly("one");
        assertThat(email.subject()).contains("Hello");
        assertThatThrownBy(() -> email.to().add(Address.of("x@example.com")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> email.headers().put("X", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> email.tags().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> email.recipients().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toBuilderCopiesEveryField() {
        Email email = minimal().from("s@example.com").replyTo("r@example.com").cc("c@example.com")
                .bcc("b@example.com").html("<p>h</p>").attach(Attachment.of("a.txt", "text/plain", new byte[] {1}))
                .header("X-A", "1").tag("t").metadata("k", "v").sendAt(Instant.EPOCH).idempotencyKey("key")
                .build();
        Email copy = email.toBuilder().build();
        assertThat(copy).isEqualTo(email).hasSameHashCodeAs(email);
        assertThat(email.toBuilder().subject("Other").build()).isNotEqualTo(email);
    }

    @Test
    void toStringShowsCountsOnly() {
        Email email = minimal().cc("secret.cc@example.com").tag("booking")
                .attach(Attachment.of("a.pdf", "application/pdf", new byte[] {1})).build();
        assertThat(email.toString()).isEqualTo("Email[to=1, cc=1, bcc=0, attachments=1, tags=1]")
                .doesNotContain("player").doesNotContain("Hello").doesNotContain("Hi there");
    }

    @Test
    void nullArgumentsThrowNullPointerException() {
        assertThatThrownBy(() -> minimal().subject(null)).isInstanceOf(NullPointerException.class).hasMessage("subject");
        assertThatThrownBy(() -> minimal().to((String) null)).isInstanceOf(NullPointerException.class).hasMessage("to");
        assertThatThrownBy(() -> minimal().header("X", null)).isInstanceOf(NullPointerException.class);
    }

    private static java.util.Map.Entry<String, String> entry(String key, String value) {
        return new java.util.AbstractMap.SimpleEntry<String, String>(key, value);
    }
}
