package io.primelogiclab.mailroom.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Newer-JDK features found when the code runs, so one set of Java 8 class files can use them on Java 21 and newer.
 * The only place in mailroom-core that uses reflection. Not API: this package may change in any release.
 */
public final class Jdk {

    private static final Method NEW_VIRTUAL_THREAD_EXECUTOR = find(Executors.class, "newVirtualThreadPerTaskExecutor");
    private static final Method IS_VIRTUAL = find(Thread.class, "isVirtual");

    private Jdk() {
    }

    /**
     * Creates an executor that starts a virtual thread per task, on Java 21 and newer.
     *
     * @return the executor, or null where virtual threads are missing or not usable (Java 8, or Java 19 and 20
     *         without preview features)
     */
    public static ExecutorService newVirtualThreadExecutor() {
        if (NEW_VIRTUAL_THREAD_EXECUTOR == null) {
            return null;
        }
        try {
            return (ExecutorService) NEW_VIRTUAL_THREAD_EXECUTOR.invoke(null);
        } catch (IllegalAccessException e) {
            return null;
        } catch (InvocationTargetException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Tells whether a thread is virtual.
     *
     * @param thread the thread
     * @return true for a virtual thread; always false before Java 21
     */
    public static boolean isVirtual(Thread thread) {
        if (IS_VIRTUAL == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(IS_VIRTUAL.invoke(thread));
        } catch (IllegalAccessException e) {
            return false;
        } catch (InvocationTargetException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Method find(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }
}
