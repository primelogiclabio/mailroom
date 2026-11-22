package io.primelogiclab.mailroom.internal;

/**
 * Character checks shared by mailroom's validation. Not API: this package may change in any release.
 */
public final class Chars {

    private Chars() {
    }

    /**
     * Tells whether the text contains CR, LF or any other ISO control character.
     *
     * @param text the text to check
     * @return true if a control character is present
     */
    public static boolean hasControl(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the text contains a control character other than horizontal tab.
     *
     * @param text the text to check
     * @return true if a control character other than tab is present
     */
    public static boolean hasControlExceptTab(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\t' && Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether every character is visible ASCII (0x21 to 0x7E), so no spaces, controls or non-ASCII.
     *
     * @param text the text to check
     * @return true if the text is non-empty visible ASCII
     */
    public static boolean isVisibleAscii(CharSequence text) {
        if (text.length() == 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether the text is 1 to {@code maxLength} ASCII letters, digits, underscores or hyphens.
     *
     * @param text      the text to check
     * @param maxLength the longest allowed length
     * @return true if the text is such a token
     */
    public static boolean isToken(CharSequence text, int maxLength) {
        if (text.length() == 0 || text.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether the text is empty or only whitespace (Java 8 has no {@code String.isBlank}).
     *
     * @param text the text to check
     * @return true if blank
     */
    public static boolean isBlank(String text) {
        return text.trim().isEmpty();
    }
}
