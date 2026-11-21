package io.primelogiclab.mailroom;

import io.primelogiclab.mailroom.internal.Chars;
import io.primelogiclab.mailroom.internal.Masking;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An email address with an optional display name, such as {@code PrimeLogicLab <no-reply@primelogiclab.io>}.
 *
 * <p>Addresses are checked when they are created and are immutable. The rules are deliberately strict and ASCII-only:
 * <ul>
 *   <li>the part before {@code @} is a dot-atom of at most 64 characters: letters, digits and
 *       {@code !#$%&'*+-/=?^_`{|}~}, with single dots between them; quoted local parts are not supported;</li>
 *   <li>the domain is one or more labels of 1 to 63 letters, digits or hyphens (not at either end), at most 253
 *       characters in all; internationalised domains must be given in punycode ({@code xn--}); domain literals such as
 *       {@code [192.0.2.1]} are not supported; single-label domains such as {@code localhost} are allowed;</li>
 *   <li>the whole address is at most 254 characters, and the domain is stored in lower case;</li>
 *   <li>the display name may use any language but must not contain line breaks or other control characters.</li>
 * </ul>
 *
 * <p>{@link #toString()} is masked ({@code p***@example.com}) so an address never reaches a log by accident; use
 * {@link #format()} or {@link #address()} for the full value.
 *
 * @since 0.1.0
 */
public final class Address {

    private static final int MAX_ADDRESS = 254;
    private static final int MAX_LOCAL_PART = 64;
    private static final int MAX_DOMAIN = 253;
    private static final int MAX_LABEL = 63;
    private static final String LOCAL_PART_SYMBOLS = "!#$%&'*+-/=?^_`{|}~";
    private static final String NAME_SPECIALS = "()<>[]:;@\\,.\"";

    private final String displayName;
    private final String localPart;
    private final String domain;

    private Address(String displayName, String localPart, String domain) {
        this.displayName = displayName;
        this.localPart = localPart;
        this.domain = domain;
    }

    /**
     * Creates an address without a display name.
     *
     * @param address the address, such as {@code player@example.com}
     * @return the address
     * @throws InvalidEmailException if the address breaks a rule
     * @since 0.1.0
     */
    public static Address of(String address) {
        Objects.requireNonNull(address, "address");
        return create(null, address.trim());
    }

    /**
     * Creates an address with a display name.
     *
     * @param displayName the name shown to the recipient, such as {@code PrimeLogicLab}; {@code null} or blank for none
     * @param address     the address, such as {@code no-reply@primelogiclab.io}
     * @return the address
     * @throws InvalidEmailException if the name or the address breaks a rule
     * @since 0.1.0
     */
    public static Address of(String displayName, String address) {
        Objects.requireNonNull(address, "address");
        return create(displayName, address.trim());
    }

    /**
     * Parses {@code player@example.com}, {@code Name <player@example.com>} or {@code "Last, First" <a@b.io>}. A quoted
     * display name may contain {@code \"} and {@code \\}.
     *
     * @param text the address as written in an email header
     * @return the address
     * @throws InvalidEmailException if the text is not an address this class accepts
     * @since 0.1.0
     */
    public static Address parse(String text) {
        Objects.requireNonNull(text, "text");
        String s = text.trim();
        if (s.isEmpty()) {
            throw new InvalidEmailException("the address is empty");
        }
        if (s.endsWith(">")) {
            int open = s.lastIndexOf('<');
            if (open < 0) {
                throw new InvalidEmailException("the address has a '>' without a matching '<'");
            }
            String name = parseDisplayName(s.substring(0, open).trim());
            return create(name, s.substring(open + 1, s.length() - 1).trim());
        }
        if (s.indexOf('<') >= 0 || s.indexOf('>') >= 0) {
            throw new InvalidEmailException("the address has unbalanced angle brackets");
        }
        return create(null, s);
    }

    /**
     * Returns the address without the display name, such as {@code player@example.com}.
     *
     * @return the full address
     * @since 0.1.0
     */
    public String address() {
        return localPart + "@" + domain;
    }

    /**
     * Returns the display name, if there is one.
     *
     * @return the display name, never blank
     * @since 0.1.0
     */
    public Optional<String> displayName() {
        return Optional.ofNullable(displayName);
    }

    /**
     * Returns the part before {@code @}.
     *
     * @return the local part, as given
     * @since 0.1.0
     */
    public String localPart() {
        return localPart;
    }

    /**
     * Returns the part after {@code @}, in lower case.
     *
     * @return the domain
     * @since 0.1.0
     */
    public String domain() {
        return domain;
    }

    /**
     * Formats the address for an email header: {@code Name <player@example.com>}, or just the address when there is no
     * display name. The name is quoted when it contains special characters. Non-ASCII names are returned as they are;
     * transports encode them as their protocol requires.
     *
     * @return the full formatted address
     * @since 0.1.0
     */
    public String format() {
        if (displayName == null) {
            return address();
        }
        return quoteIfNeeded(displayName) + " <" + address() + ">";
    }

    /**
     * Returns the masked address, such as {@code p***@example.com}, so logging an address never leaks it. Use
     * {@link #format()} or {@link #address()} for the full value.
     *
     * @return the masked address, without the display name
     */
    @Override
    public String toString() {
        return Masking.address(address());
    }

    /**
     * Two addresses are equal when their display names, local parts and (lower-cased) domains are equal.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Address)) {
            return false;
        }
        Address that = (Address) other;
        return Objects.equals(displayName, that.displayName) && localPart.equals(that.localPart)
                && domain.equals(that.domain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayName, localPart, domain);
    }

    private static Address create(String displayName, String address) {
        String name = null;
        if (displayName != null) {
            if (Chars.hasControl(displayName)) {
                throw new InvalidEmailException("the display name contains a line break or another control character");
            }
            String trimmed = displayName.trim();
            name = trimmed.isEmpty() ? null : trimmed;
        }
        if (address.isEmpty()) {
            throw new InvalidEmailException("the address is empty");
        }
        if (address.length() > MAX_ADDRESS) {
            throw new InvalidEmailException("the address is longer than " + MAX_ADDRESS + " characters");
        }
        int at = address.lastIndexOf('@');
        if (at < 0) {
            throw new InvalidEmailException("the address has no '@'");
        }
        if (address.indexOf('@') != at) {
            throw new InvalidEmailException("the address has more than one '@'");
        }
        String local = address.substring(0, at);
        String domain = address.substring(at + 1);
        checkLocalPart(local);
        checkDomain(domain);
        return new Address(name, local, domain.toLowerCase(Locale.ROOT));
    }

    private static void checkLocalPart(String local) {
        if (local.isEmpty()) {
            throw new InvalidEmailException("the part before '@' is empty");
        }
        if (local.length() > MAX_LOCAL_PART) {
            throw new InvalidEmailException("the part before '@' is longer than " + MAX_LOCAL_PART + " characters");
        }
        if (local.charAt(0) == '"') {
            throw new InvalidEmailException("quoted local parts are not supported");
        }
        if (local.charAt(0) == '.' || local.charAt(local.length() - 1) == '.' || local.contains("..")) {
            throw new InvalidEmailException("the part before '@' has a dot at the start, at the end or twice in a row");
        }
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c > 0x7E) {
                throw new InvalidEmailException("the part before '@' must be ASCII");
            }
            if (!isLetterOrDigit(c) && c != '.' && LOCAL_PART_SYMBOLS.indexOf(c) < 0) {
                throw new InvalidEmailException("the part before '@' contains a space or a character that is not allowed");
            }
        }
    }

    private static void checkDomain(String domain) {
        if (domain.isEmpty()) {
            throw new InvalidEmailException("the domain is empty");
        }
        if (domain.charAt(0) == '[') {
            throw new InvalidEmailException("domain literals such as [192.0.2.1] are not supported");
        }
        if (domain.length() > MAX_DOMAIN) {
            throw new InvalidEmailException("the domain is longer than " + MAX_DOMAIN + " characters");
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()) {
                throw new InvalidEmailException("the domain has an empty label (a dot at the start, at the end or twice)");
            }
            if (label.length() > MAX_LABEL) {
                throw new InvalidEmailException("a domain label is longer than " + MAX_LABEL + " characters");
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (c > 0x7E) {
                    throw new InvalidEmailException(
                            "the domain must be ASCII; give internationalised domains in punycode (xn--)");
                }
                if (!isLetterOrDigit(c) && c != '-') {
                    throw new InvalidEmailException("the domain contains a space or a character that is not allowed");
                }
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw new InvalidEmailException("a domain label starts or ends with '-'");
            }
        }
    }

    private static String parseDisplayName(String name) {
        if (name.isEmpty()) {
            return null;
        }
        if (name.charAt(0) != '"') {
            if (name.indexOf('"') >= 0) {
                throw new InvalidEmailException("the display name has a stray quote; quote the whole name");
            }
            return name;
        }
        if (name.length() < 2 || name.charAt(name.length() - 1) != '"') {
            throw new InvalidEmailException("the display name has an unclosed quote");
        }
        StringBuilder unquoted = new StringBuilder();
        int end = name.length() - 1;
        for (int i = 1; i < end; i++) {
            char c = name.charAt(i);
            if (c == '\\') {
                if (i + 1 >= end) {
                    throw new InvalidEmailException("the display name has an unclosed quote");
                }
                unquoted.append(name.charAt(++i));
            } else if (c == '"') {
                throw new InvalidEmailException("the display name has a quote that is not escaped");
            } else {
                unquoted.append(c);
            }
        }
        return unquoted.toString();
    }

    private static String quoteIfNeeded(String name) {
        boolean needsQuotes = false;
        for (int i = 0; i < name.length() && !needsQuotes; i++) {
            needsQuotes = NAME_SPECIALS.indexOf(name.charAt(i)) >= 0;
        }
        if (!needsQuotes) {
            return name;
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '"' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('"').toString();
    }

    private static boolean isLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
