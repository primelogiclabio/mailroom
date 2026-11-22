package io.primelogiclab.mailroom.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data before it reaches logs, exception messages or {@code toString()}. Not API: this package may
 * change in any release.
 */
public final class Masking {

    private static final Pattern ADDRESS = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*");

    private Masking() {
    }

    /**
     * Masks every email address in free text, such as a provider's error message:
     * {@code "550 player@example.com unknown"} becomes {@code "550 p***@example.com unknown"}.
     *
     * @param text the text
     * @return the text with every address masked
     */
    public static String addressesIn(String text) {
        Matcher m = ADDRESS.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuffer out = new StringBuffer();
        do {
            m.appendReplacement(out, Matcher.quoteReplacement(address(m.group())));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
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
