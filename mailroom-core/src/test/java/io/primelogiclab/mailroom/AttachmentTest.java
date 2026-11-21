package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    @Test
    void createsARegularAttachment() {
        Attachment a = Attachment.of("scorecard.pdf", "application/pdf", PDF);
        assertThat(a.filename()).contains("scorecard.pdf");
        assertThat(a.contentType()).isEqualTo("application/pdf");
        assertThat(a.contentId()).isEmpty();
        assertThat(a.isInline()).isFalse();
        assertThat(a.size()).isEqualTo(4);
        assertThat(a.content()).containsExactly(PDF);
    }

    @Test
    void createsInlineParts() {
        Attachment logo = Attachment.inline("logo", "image/png", new byte[] {1, 2});
        assertThat(logo.isInline()).isTrue();
        assertThat(logo.contentId()).contains("logo");
        assertThat(logo.filename()).isEmpty();
        assertThat(Attachment.inline("logo", "logo.png", "image/png", new byte[] {1}).filename()).contains("logo.png");
    }

    @Test
    void copiesTheContentOnCreateAndOnRead() {
        byte[] bytes = {1, 2, 3};
        Attachment a = Attachment.of("a.bin", "application/octet-stream", bytes);
        bytes[0] = 9;
        assertThat(a.content()).containsExactly(1, 2, 3);
        a.content()[1] = 9;
        assertThat(a.content()).containsExactly(1, 2, 3);
    }

    @Test
    void openStreamReadsTheContent() throws Exception {
        Attachment a = Attachment.of("a.bin", "application/octet-stream", new byte[] {5, 6, 7});
        try (InputStream in = a.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                out.write(b);
            }
            assertThat(out.toByteArray()).containsExactly(5, 6, 7);
        }
    }

    @Test
    void allowsEmptyContentAndContentTypeParameters() {
        assertThat(Attachment.of("empty.txt", "text/plain; charset=UTF-8", new byte[0]).size()).isZero();
        assertThat(Attachment.of("a.txt", "text/plain;format=\"flowed\"", PDF).contentType())
                .isEqualTo("text/plain;format=\"flowed\"");
    }

    @Test
    void equalityComparesBytes() {
        assertThat(Attachment.of("a.pdf", "application/pdf", new byte[] {1}))
                .isEqualTo(Attachment.of("a.pdf", "application/pdf", new byte[] {1}))
                .hasSameHashCodeAs(Attachment.of("a.pdf", "application/pdf", new byte[] {1}))
                .isNotEqualTo(Attachment.of("a.pdf", "application/pdf", new byte[] {2}));
    }

    @Test
    void toStringLeavesOutTheFilenameAndContent() {
        assertThat(Attachment.of("jo-smith-invoice.pdf", "application/pdf", PDF).toString())
                .isEqualTo("Attachment[attachment, application/pdf, 4 bytes]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "../etc/passwd", "a\\b.pdf", "bad\r\nname.pdf", "tab\tname.pdf"})
    void rejectsBadFilenames(String filename) {
        assertThatThrownBy(() -> Attachment.of(filename, "application/pdf", PDF))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("filename");
    }

    @Test
    void rejectsOverLongFilenames() {
        assertThatThrownBy(() -> Attachment.of(AddressTest.repeat('a', 256), "application/pdf", PDF))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("255");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "pdf", "application/", "/pdf", "text/plain\r\nX: y", "text/plain; charset", "a b/c"})
    void rejectsBadContentTypes(String contentType) {
        assertThatThrownBy(() -> Attachment.of("a.pdf", contentType, PDF))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("content type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<logo>", "lo go", "logo\r\n", "lögo"})
    void rejectsBadContentIds(String contentId) {
        assertThatThrownBy(() -> Attachment.inline(contentId, "image/png", PDF))
                .isInstanceOf(InvalidEmailException.class).hasMessageContaining("content id");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> Attachment.inline("<bad>", "bad/", "bad", PDF))
                .isInstanceOf(InvalidEmailException.class)
                .satisfies(e -> assertThat(((InvalidEmailException) e).problems()).hasSize(3));
    }

    @Test
    void nullArgumentsThrowNullPointerException() {
        assertThatThrownBy(() -> Attachment.of("a.pdf", "application/pdf", null))
                .isInstanceOf(NullPointerException.class).hasMessage("content");
    }
}
