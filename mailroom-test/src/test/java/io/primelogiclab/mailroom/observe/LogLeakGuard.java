package io.primelogiclab.mailroom.observe;

import io.primelogiclab.mailroom.internal.Observer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails any test whose mailroom log records contain a full email address, the {@link #CANARY} secret or an attached
 * {@link Throwable}. JUnit loads it for every test in this module (autodetection, see
 * {@code junit-platform.properties}), and it turns the mailroom logger up to {@link Level#ALL}, so every FINE line
 * is built and checked too. Records go only to this guard, keeping the build output quiet.
 */
public final class LogLeakGuard implements BeforeEachCallback, AfterEachCallback {

    /** A secret value for tests to configure; it must never appear in a log record. */
    public static final String CANARY = "canary-secret-7f3a9c";

    /** Anything shaped like an address; the same pattern core's {@code Masking} uses. */
    private static final Pattern ADDRESS = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*");
    /** A local part as {@code Masking} leaves it: at most one character, then {@code ***}. */
    private static final Pattern MASKED_LOCAL_PART = Pattern.compile("[^*]?\\*{3,4}");

    private static final Capture CAPTURE = new Capture();
    /** Held strongly, because JUL forgets the level of a logger nobody references. */
    private static final Logger LOGGER = install();

    private static Logger install() {
        Logger logger = Logger.getLogger(Observer.LOGGER_NAME);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        CAPTURE.setLevel(Level.ALL);
        logger.addHandler(CAPTURE);
        return logger;
    }

    /**
     * Returns the formatted mailroom log records captured since the current test started, as {@code LEVEL text}.
     *
     * @return the lines, oldest first
     */
    public static List<String> lines() {
        List<String> lines = new ArrayList<String>();
        for (LogRecord record : CAPTURE.records) {
            lines.add(record.getLevel().getName() + " " + text(record));
        }
        return lines;
    }

    /**
     * Returns the captured records since the current test started.
     *
     * @return the records, oldest first
     */
    public static List<LogRecord> records() {
        return new ArrayList<LogRecord>(CAPTURE.records);
    }

    /**
     * Returns what is wrong with a log line: an unmasked address or the canary secret.
     *
     * @param text the formatted line
     * @return the problems; empty if the line is safe
     */
    public static List<String> leaks(String text) {
        List<String> leaks = new ArrayList<String>();
        Matcher m = ADDRESS.matcher(text);
        while (m.find()) {
            String local = m.group().substring(0, m.group().lastIndexOf('@'));
            if (!MASKED_LOCAL_PART.matcher(local).matches()) {
                leaks.add("a full email address");
                break;
            }
        }
        if (text.contains(CANARY)) {
            leaks.add("the canary secret");
        }
        return leaks;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        CAPTURE.records.clear();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<String> problems = new ArrayList<String>();
        for (LogRecord record : CAPTURE.records) {
            String text = text(record);
            for (String leak : leaks(text)) {
                problems.add(leak + " in: " + text);
            }
            if (record.getThrown() != null) {
                problems.add("a Throwable attached to: " + text);
            }
        }
        CAPTURE.records.clear();
        if (!problems.isEmpty()) {
            throw new AssertionError("mailroom logged personal data or secrets: " + problems);
        }
    }

    private static String text(LogRecord record) {
        return new SimpleFormatter().formatMessage(record);
    }

    /** Keeps every record published to the mailroom logger. */
    private static final class Capture extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
