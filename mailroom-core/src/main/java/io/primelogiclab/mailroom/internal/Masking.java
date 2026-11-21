package io.primelogiclab.mailroom.internal;

/**
 * Masks personal data before it reaches logs, exception messages or {@code toString()}. Not API: this package may
 * change in any release.
 */
public final class Masking {

    private Masking() {
    }

    /**
     * Masks an email address to its first character and domain: {@code player@example.com} becomes
     * {@code p***@example.com}. Text without an {@code @} becomes {@code ***}.
     *
     * @param address the address to mask
     * @return the masked form, never the full address
     */
    public static String address(String address) {
        int at = address.lastIndexOf('@');
        if (at < 0) {
            return "***";
        }
        String domain = address.substring(at + 1);
        return at == 0 ? "***@" + domain : address.charAt(0) + "***@" + domain;
    }
}
