package io.primelogiclab.mailroom.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mailroom-core's own version and the version check for {@code TransportProvider.requiresCore()}. Not API: this
 * package may change in any release.
 */
public final class CoreVersion {

    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?");
    private static final String CURRENT = load();

    private CoreVersion() {
    }

    /**
     * Returns the running mailroom-core version, such as {@code 0.1.0}, from the build-filtered
     * {@code version.properties}.
     *
     * @return the version
     */
    public static String current() {
        return CURRENT;
    }

    /**
     * Tells whether {@code actual} is at least {@code required}, comparing major, minor and patch as numbers (so
     * {@code 1.10} is newer than {@code 1.2}) and ignoring qualifiers such as {@code -SNAPSHOT}. Missing parts count
     * as zero.
     *
     * @param required the minimum, such as {@code 1.2}
     * @param actual   the version found, such as {@code 1.10.0-SNAPSHOT}
     * @return true if {@code actual} satisfies {@code required}
     * @throws IllegalArgumentException if either is not a version
     */
    public static boolean satisfies(String required, String actual) {
        int[] r = parse(required);
        int[] a = parse(actual);
        for (int i = 0; i < 3; i++) {
            if (a[i] != r[i]) {
                return a[i] > r[i];
            }
        }
        return true;
    }

    /**
     * Parses {@code major[.minor[.patch]][qualifier]} into three numbers.
     *
     * @param version the version text
     * @return major, minor and patch
     * @throws IllegalArgumentException if the text is not a version
     */
    static int[] parse(String version) {
        if (version == null) {
            throw new IllegalArgumentException("not a version: null");
        }
        Matcher m = VERSION.matcher(version.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a version: " + version);
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), part(m.group(2)), part(m.group(3))};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a version: " + version, e);
        }
    }

    private static int part(String group) {
        return group == null ? 0 : Integer.parseInt(group);
    }

    private static String load() {
        InputStream in = CoreVersion.class.getResourceAsStream("version.properties");
        if (in != null) {
            try {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.startsWith("${")) {
                    return version.trim();
                }
            } catch (IOException e) {
                // fall through to the manifest
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        String fromManifest = CoreVersion.class.getPackage().getImplementationVersion();
        return fromManifest != null ? fromManifest : "0.0.0";
    }
}
