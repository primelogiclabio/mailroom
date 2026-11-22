package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.Chars;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A file attached to an email: either a regular attachment ({@link #of}) or an inline part that the HTML body shows
 * through a {@code cid:} link ({@link #inline}).
 *
 * <p>Attachments are immutable. The content is copied once when the attachment is created; {@link #openStream()}
 * reads it without another copy, so transports can encode large files without doubling the memory they use.
 *
 * <p>Rules: a filename has no line breaks or other control characters and no {@code /} or {@code \}; a content type is
 * {@code type/subtype} with optional {@code ; name=value} parameters; a content id is visible ASCII without
 * {@code <}, {@code >} or spaces (give {@code logo}, not {@code <logo>}).
 *
 * @since 0.1.0
 */
public final class Attachment {

    private static final int MAX_FILENAME = 255;
    private static final String TOKEN = "[A-Za-z0-9!#$&^_.+-]+";
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            TOKEN + "/" + TOKEN + "(\\s*;\\s*" + TOKEN + "=(" + TOKEN + "|\"[^\"\\\\\\p{Cntrl}]*\"))*");

    private final String filename;
    private final String contentType;
    private final String contentId;
    private final byte[] content;

    private Attachment(String filename, String contentType, String contentId, byte[] content) {
        this.filename = filename;
        this.contentType = contentType;
        this.contentId = contentId;
        this.content = content.clone();
    }

    /**
     * Creates a regular attachment.
     *
     * @param filename    the file name the recipient sees, such as {@code scorecard.pdf}
     * @param contentType the media type, such as {@code application/pdf}
     * @param content     the file's bytes; copied, so later changes to the array don't affect the attachment
     * @return the attachment
     * @throws InvalidEmailException if the filename or the content type breaks a rule
     * @since 0.1.0
     */
    public static Attachment of(String filename, String contentType, byte[] content) {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        List<String> problems = new ArrayList<>();
        checkFilename(filename, problems);
        checkContentType(contentType, problems);
        if (!problems.isEmpty()) {
            throw new InvalidEmailException(problems);
        }
        return new Attachment(filename, contentType.trim(), null, content);
    }

    /**
     * Creates an inline part without a filename, shown in the HTML body through {@code cid:<contentId>}.
     *
     * @param contentId   the id the HTML refers to, such as {@code logo} for {@code <img src="cid:logo">}
     * @param contentType the media type, such as {@code image/png}
     * @param content     the bytes; copied, so later changes to the array don't affect the attachment
     * @return the inline attachment
     * @throws InvalidEmailException if the content id or the content type breaks a rule
     * @since 0.1.0
     */
    public static Attachment inline(String contentId, String contentType, byte[] content) {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        return createInline(contentId, null, contentType, content);
    }

    /**
     * Creates an inline part with a filename, shown in the HTML body through {@code cid:<contentId>}.
     *
     * @param contentId   the id the HTML refers to, such as {@code logo}
     * @param filename    the file name, such as {@code logo.png}
     * @param contentType the media type, such as {@code image/png}
     * @param content     the bytes; copied, so later changes to the array don't affect the attachment
     * @return the inline attachment
     * @throws InvalidEmailException if the content id, the filename or the content type breaks a rule
     * @since 0.1.0
     */
    public static Attachment inline(String contentId, String filename, String contentType, byte[] content) {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        return createInline(contentId, filename, contentType, content);
    }

    /**
     * Returns the file name, if there is one. Regular attachments always have one.
     *
     * @return the file name
     * @since 0.1.0
     */
    public Optional<String> filename() {
        return Optional.ofNullable(filename);
    }

    /**
     * Returns the media type, such as {@code application/pdf}.
     *
     * @return the content type
     * @since 0.1.0
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Returns the content id of an inline part, without angle brackets.
     *
     * @return the content id; empty for a regular attachment
     * @since 0.1.0
     */
    public Optional<String> contentId() {
        return Optional.ofNullable(contentId);
    }

    /**
     * Tells whether this is an inline part shown in the HTML body.
     *
     * @return true for {@link #inline} attachments
     * @since 0.1.0
     */
    public boolean isInline() {
        return contentId != null;
    }

    /**
     * Returns the size of the content.
     *
     * @return the number of bytes
     * @since 0.1.0
     */
    public int size() {
        return content.length;
    }

    /**
     * Returns a copy of the content.
     *
     * @return the bytes; changing the array doesn't affect the attachment
     * @since 0.1.0
     */
    public byte[] content() {
        return content.clone();
    }

    /**
     * Opens a stream over the content without copying it.
     *
     * @return a new stream positioned at the first byte
     * @since 0.1.0
     */
    public InputStream openStream() {
        return new ByteArrayInputStream(content);
    }

    /**
     * Two attachments are equal when their filenames, content types, content ids and bytes are equal.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Attachment that)) {
            return false;
        }
        return Objects.equals(filename, that.filename) && contentType.equals(that.contentType)
                && Objects.equals(contentId, that.contentId) && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(filename, contentType, contentId) + Arrays.hashCode(content);
    }

    /**
     * Describes the attachment without its file name or content, such as {@code Attachment[inline, image/png, 1234
     * bytes]}.
     *
     * @return a description safe to log
     */
    @Override
    public String toString() {
        return "Attachment[" + (isInline() ? "inline" : "attachment") + ", " + contentType + ", " + content.length
                + " bytes]";
    }

    private static Attachment createInline(String contentId, String filename, String contentType, byte[] content) {
        List<String> problems = new ArrayList<>();
        if (!Chars.isVisibleAscii(contentId) || contentId.indexOf('<') >= 0 || contentId.indexOf('>') >= 0) {
            problems.add("an inline attachment's content id must be visible ASCII without '<', '>' or spaces");
        }
        if (filename != null) {
            checkFilename(filename, problems);
        }
        checkContentType(contentType, problems);
        if (!problems.isEmpty()) {
            throw new InvalidEmailException(problems);
        }
        return new Attachment(filename, contentType.trim(), contentId, content);
    }

    private static void checkFilename(String filename, List<String> problems) {
        if (Chars.isBlank(filename)) {
            problems.add("an attachment's filename must not be blank");
        } else if (filename.length() > MAX_FILENAME) {
            problems.add("an attachment's filename is longer than " + MAX_FILENAME + " characters");
        } else if (Chars.hasControl(filename) || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            problems.add("an attachment's filename must not contain line breaks, control characters, '/' or '\\'");
        }
    }

    private static void checkContentType(String contentType, List<String> problems) {
        if (!CONTENT_TYPE.matcher(contentType.trim()).matches()) {
            problems.add("an attachment's content type must look like type/subtype, such as application/pdf");
        }
    }
}
