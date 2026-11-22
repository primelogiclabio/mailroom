package io.primelogiclab.mailroom.http;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the JDK's {@code HttpURLConnection} from logging credentials when an application turns on debug logging in
 * general. The JDK logs every request header, API keys included, at {@code FINE} on
 * {@code sun.net.www.protocol.http.HttpURLConnection}. While the application hasn't set that logger's level itself, it
 * is pinned to {@code INFO}, so a root logger at {@code FINE} (or DEBUG through an SLF4J bridge) shows no keys. A level
 * the application did set, even {@code FINE}, is left alone; one {@code WARNING} per JVM says what it costs.
 */
final class JdkHttpLogging {

    static final String JDK_LOGGER = "sun.net.www.protocol.http.HttpURLConnection";

    static {
        // On Java 8, the JDK's own logger is a separate object (sharing handlers but not the level) when application
        // code asked for this name first, so the JDK class makes its logger before Logger.getLogger below looks it up.
        // On 9 and later the two share their configuration either way.
        try {
            Class.forName(JDK_LOGGER);
        } catch (ClassNotFoundException e) {
            // another JDK: nothing to keep in check
        } catch (LinkageError e) {
            // the same
        } catch (SecurityException e) {
            // the same
        }
    }

    /** Held strongly: JUL forgets the level of a logger nobody references. */
    private static final Logger JDK = Logger.getLogger(JDK_LOGGER);
    private static final Logger LOG = Logger.getLogger("io.primelogiclab.mailroom.http");
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private JdkHttpLogging() {
    }

    /**
     * Pins the JDK logger to {@code INFO} if its level is inherited, or warns once if the application set it to
     * {@code FINE} or below. Cheap enough for every request, which also pins it again after a
     * {@code LogManager.readConfiguration()} cleared the levels.
     */
    static void guard() {
        Level level = JDK.getLevel();
        if (level == null) {
            JDK.setLevel(Level.INFO);
        } else if (level.intValue() <= Level.FINE.intValue() && WARNED.compareAndSet(false, true)) {
            LOG.warning(JDK_LOGGER + " is at FINE or below, so the JDK logs every request header, credentials "
                    + "included; set it to INFO or above");
        }
    }

    /** The JDK's logger, for tests: on Java 8, asking for it by name before this class loads gives another object. */
    static Logger logger() {
        return JDK;
    }

    /** Lets a test see the warning again. */
    static void resetForTests() {
        WARNED.set(false);
    }
}
