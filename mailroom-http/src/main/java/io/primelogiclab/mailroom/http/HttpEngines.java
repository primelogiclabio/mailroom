package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.MailroomException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the {@link HttpEngine} to use. Engine modules register their engine for {@link ServiceLoader}; with none on
 * the class path, the built-in {@code HttpURLConnection} engine is used, which works on every Java version.
 *
 * @since 0.1.0
 */
public final class HttpEngines {

    private HttpEngines() {
    }

    /**
     * Finds the engine registered on the thread's context class loader, or the {@code HttpURLConnection} engine if
     * none is.
     *
     * @return a new engine; the caller closes it
     * @throws MailroomException if an engine can't be loaded, or more than one is registered
     * @since 0.1.0
     */
    public static HttpEngine load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return load(loader != null ? loader : HttpEngines.class.getClassLoader());
    }

    /**
     * Finds the engine registered on the given class loader, or the {@code HttpURLConnection} engine if none is.
     *
     * @param loader the class loader to search
     * @return a new engine; the caller closes it
     * @throws MailroomException if an engine can't be loaded, or more than one is registered
     * @since 0.1.0
     */
    public static HttpEngine load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        List<HttpEngine> found = new ArrayList<HttpEngine>();
        Iterator<HttpEngine> it = ServiceLoader.load(HttpEngine.class, loader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                closeAll(found);
                throw new MailroomException("A mailroom HttpEngine could not be loaded: " + e.getMessage(), e);
            }
        }
        if (found.isEmpty()) {
            return urlConnection();
        }
        if (found.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (HttpEngine engine : found) {
                names.append(names.length() == 0 ? "" : ", ").append(engine.name()).append(" (")
                        .append(engine.getClass().getName()).append(')');
            }
            closeAll(found);
            throw new MailroomException("more than one HttpEngine is registered: " + names
                    + "; keep one engine module on the class path");
        }
        return found.get(0);
    }

    /**
     * Returns the built-in engine on {@code HttpURLConnection}, named {@code urlconnection}. It uses the JVM's proxy
     * settings and default TLS setup, keeps connections alive between calls, and holds nothing that needs closing.
     *
     * <p>It streams every body so that {@code HttpURLConnection} can't re-send it, and in that mode the JDK drops the
     * body of a 401 or 407 response unread: those come back with their status and headers but an empty body.
     *
     * @return the engine
     * @since 0.1.0
     */
    public static HttpEngine urlConnection() {
        return new UrlConnectionEngine();
    }

    private static void closeAll(List<HttpEngine> engines) {
        for (HttpEngine engine : engines) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                // already failing; the load error matters more
            }
        }
    }
}
