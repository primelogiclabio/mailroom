package io.primelogiclab.mailroom.apicheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Java 21 set and the Java 8 set have the same public API, module pair by module pair, apart from the differences
 * listed in {@code api-differences.txt}. The shared tests catch drift only in the API they compile against; this
 * compares all of it.
 *
 * <p>Each set is loaded in a class loader of its own (the test classpath holds neither, so the two can't mask each
 * other), without running static initializers. The API is every public type outside {@code internal} packages, and its
 * public and protected constructors, methods and fields, each written as one line: modifiers, generic signature,
 * {@code throws}, and for a type its kind, supertypes and {@code sealed ... permits}. In a final type, {@code final} on
 * a method is dropped, since it changes nothing for callers.
 */
class ApiParityTest {

    private static final List<String> MODULES = List.of("core", "http", "test", "tck");
    private static final String LIST = "api-differences.txt";
    private static final int TYPE_MODIFIERS =
            Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT;
    private static final int METHOD_MODIFIERS =
            Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT;
    private static final int FIELD_MODIFIERS =
            Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL;

    private static final Map<String, URLClassLoader> LOADERS = new HashMap<>();

    @AfterAll
    static void closeLoaders() throws IOException {
        for (URLClassLoader loader : LOADERS.values()) {
            loader.close();
        }
    }

    @ParameterizedTest(name = "mailroom-{0}")
    @ValueSource(strings = {"core", "http", "test", "tck"})
    void bothSetsHaveTheSamePublicApi(String module) {
        SortedSet<String> java21 = api(module, "21");
        SortedSet<String> java8 = api(module, "8");
        SortedSet<String> differences = new TreeSet<>();
        java21.stream().filter(line -> !java8.contains(line)).forEach(line -> differences.add("21 " + line));
        java8.stream().filter(line -> !java21.contains(line)).forEach(line -> differences.add("8 " + line));

        Set<String> listed = listedDifferences().getOrDefault(module, Set.of());
        List<String> unlisted = differences.stream().filter(line -> !listed.contains(line)).toList();
        List<String> stale = listed.stream().filter(line -> !differences.contains(line)).sorted().toList();
        if (unlisted.isEmpty() && stale.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder();
        if (!unlisted.isEmpty()) {
            message.append("The public APIs of mailroom-").append(module).append(" and mailroom-").append(module)
                    .append("-java8 differ in ways ").append(LIST).append(" doesn't list. Make the sets match, or,")
                    .append(" if a difference is intended, add its line under [").append(module).append("]:\n");
            unlisted.forEach(line -> message.append(line).append('\n'));
        }
        if (!stale.isEmpty()) {
            message.append("These lines under [").append(module).append("] in ").append(LIST)
                    .append(" no longer match a difference; remove them:\n");
            stale.forEach(line -> message.append(line).append('\n'));
        }
        fail(message.toString());
    }

    @Test
    void theListHasOneSectionPerModulePairAtMost() {
        assertEquals(Set.of(), listedDifferences().keySet().stream()
                .filter(section -> !MODULES.contains(section)).collect(Collectors.toSet()),
                "sections in " + LIST + " that name no module pair");
    }

    /** The module's API in one set, one sorted line per type and member. */
    private static SortedSet<String> api(String module, String set) {
        Path artifact = artifact(module, set);
        ClassLoader loader = loader(set);
        SortedSet<String> lines = new TreeSet<>();
        for (String name : classNames(artifact)) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new AssertionError("can't load " + name + " from " + artifact, e);
            }
            if (type.getClassLoader() != loader) {
                fail(name + " came from " + type.getClassLoader() + ", not from " + artifact);
            }
            if (isApi(type)) {
                describe(type, lines);
            }
        }
        if (lines.isEmpty()) {
            fail("found no public API in " + artifact);
        }
        return lines;
    }

    private static Path artifact(String module, String set) {
        String key = "api." + module + "." + set;
        String path = System.getProperty(key);
        if (path == null || path.isEmpty() || path.startsWith("${")) {
            fail("system property " + key + " isn't set: run this test through Maven (mailroom-api-check's pom.xml)");
        }
        return Path.of(path);
    }

    /** One loader per set, holding its four modules; third-party classes come from the test classpath. */
    private static synchronized ClassLoader loader(String set) {
        return LOADERS.computeIfAbsent(set, s -> {
            assertNull(ClassLoader.getSystemClassLoader().getResource("io/primelogiclab/mailroom/Email.class"),
                    "a mailroom set is on the test classpath, so both loaders would see it");
            URL[] urls = MODULES.stream().map(module -> url(artifact(module, s))).toArray(URL[]::new);
            return new URLClassLoader("mailroom-java" + s, urls, ClassLoader.getSystemClassLoader());
        });
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Binary names of the classes in a jar or a classes directory, skipping module-info and package-info. */
    private static List<String> classNames(Path artifact) {
        List<String> paths = new ArrayList<>();
        try {
            if (Files.isDirectory(artifact)) {
                try (Stream<Path> files = Files.walk(artifact)) {
                    files.filter(Files::isRegularFile)
                            .map(file -> artifact.relativize(file).toString().replace('\\', '/'))
                            .forEach(paths::add);
                }
            } else {
                try (JarFile jar = new JarFile(artifact.toFile())) {
                    jar.stream().map(JarEntry::getName).forEach(paths::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return paths.stream()
                .filter(path -> path.endsWith(".class") && !path.startsWith("META-INF/"))
                .filter(path -> !path.endsWith("module-info.class") && !path.endsWith("package-info.class"))
                .map(path -> path.substring(0, path.length() - ".class".length()).replace('/', '.'))
                .sorted()
                .toList();
    }

    /** Public, or nested and protected, all the way out; not anonymous, local or synthetic; not internal. */
    private static boolean isApi(Class<?> type) {
        if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()) {
            return false;
        }
        String pkg = type.getPackageName();
        if (pkg.endsWith(".internal") || pkg.contains(".internal.")) {
            return false;
        }
        for (Class<?> t = type; t != null; t = t.getDeclaringClass()) {
            int modifiers = t.getModifiers();
            boolean nested = t.getDeclaringClass() != null;
            if (!Modifier.isPublic(modifiers) && !(nested && Modifier.isProtected(modifiers))) {
                return false;
            }
        }
        return true;
    }

    private static void describe(Class<?> type, SortedSet<String> lines) {
        String owner = type.getName() + ": ";
        lines.add(owner + header(type));
        boolean finalType = Modifier.isFinal(type.getModifiers());
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (visible(constructor) && !constructor.isSynthetic()) {
                lines.add(owner + constructor(type, constructor));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (visible(method) && !method.isSynthetic() && !method.isBridge()) {
                lines.add(owner + method(method, finalType));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (visible(field) && !field.isSynthetic()) {
                lines.add(owner + field(field));
            }
        }
    }

    private static boolean visible(Member member) {
        return Modifier.isPublic(member.getModifiers()) || Modifier.isProtected(member.getModifiers());
    }

    private static String header(Class<?> type) {
        int modifiers = type.getModifiers() & TYPE_MODIFIERS;
        if (type.isInterface()) {
            modifiers &= ~(Modifier.ABSTRACT | Modifier.STATIC);    // implicit
        }
        String kind = type.isAnnotation() ? "@interface"
                : type.isInterface() ? "interface"
                : type.isEnum() ? "enum"
                : type.isRecord() ? "record"
                : "class";
        StringBuilder line = new StringBuilder(prefix(modifiers));
        if (type.isSealed()) {
            line.append("sealed ");
        }
        line.append(kind).append(typeParameters(type.getTypeParameters()));
        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class) {
            line.append(" extends ").append(superclass.getTypeName());
        }
        List<String> interfaces = names(type.getGenericInterfaces());
        if (!interfaces.isEmpty()) {
            line.append(type.isInterface() ? " extends " : " implements ").append(String.join(", ", interfaces));
        }
        if (type.isSealed()) {
            line.append(" permits ").append(Arrays.stream(type.getPermittedSubclasses())
                    .map(Class::getName).sorted().collect(Collectors.joining(", ")));
        }
        return line.toString();
    }

    private static String constructor(Class<?> type, Constructor<?> constructor) {
        return prefix(constructor.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED))
                + typeParametersWithSpace(constructor.getTypeParameters())
                + type.getSimpleName() + "(" + parameters(constructor) + ")" + exceptions(constructor);
    }

    private static String method(Method method, boolean finalType) {
        int modifiers = method.getModifiers() & METHOD_MODIFIERS;
        if (finalType) {
            modifiers &= ~Modifier.FINAL;
        }
        return prefix(modifiers) + (method.isDefault() ? "default " : "")
                + typeParametersWithSpace(method.getTypeParameters())
                + method.getGenericReturnType().getTypeName() + " " + method.getName()
                + "(" + parameters(method) + ")" + exceptions(method);
    }

    private static String field(Field field) {
        return prefix(field.getModifiers() & FIELD_MODIFIERS) + field.getGenericType().getTypeName() + " "
                + field.getName();
    }

    private static String prefix(int modifiers) {
        String text = Modifier.toString(modifiers);
        return text.isEmpty() ? "" : text + " ";
    }

    private static String typeParameters(TypeVariable<?>[] variables) {
        if (variables.length == 0) {
            return "";
        }
        return Arrays.stream(variables).map(variable -> {
            List<String> bounds = names(variable.getBounds()).stream()
                    .filter(bound -> !bound.equals("java.lang.Object")).toList();
            return variable.getName() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds));
        }).collect(Collectors.joining(", ", "<", ">"));
    }

    private static String typeParametersWithSpace(TypeVariable<?>[] variables) {
        String text = typeParameters(variables);
        return text.isEmpty() ? "" : text + " ";
    }

    private static String parameters(Executable executable) {
        List<String> types = new ArrayList<>(Arrays.stream(executable.getGenericParameterTypes())
                .map(Type::getTypeName).toList());
        if (executable.isVarArgs() && !types.isEmpty()) {
            String last = types.get(types.size() - 1);
            types.set(types.size() - 1, last.substring(0, last.length() - 2) + "...");
        }
        return String.join(", ", types);
    }

    private static String exceptions(Executable executable) {
        List<String> types = names(executable.getGenericExceptionTypes()).stream().sorted().toList();
        return types.isEmpty() ? "" : " throws " + String.join(", ", types);
    }

    private static List<String> names(Type[] types) {
        return Arrays.stream(types).map(Type::getTypeName).sorted().toList();
    }

    /** The entries of api-differences.txt by section: "[core]" and so on, then "21 line" or "8 line". */
    private static Map<String, Set<String>> listedDifferences() {
        Map<String, Set<String>> sections = new LinkedHashMap<>();
        try (InputStream in = ApiParityTest.class.getResourceAsStream("/" + LIST)) {
            if (in == null) {
                fail(LIST + " is missing from the test resources");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String section = null;
            int number = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                number++;
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    if (sections.putIfAbsent(section, new TreeSet<>()) != null) {
                        fail(LIST + " line " + number + ": section [" + section + "] appears twice");
                    }
                } else if (section == null) {
                    fail(LIST + " line " + number + ": an entry before the first [module] section");
                } else if (!line.startsWith("21 ") && !line.startsWith("8 ")) {
                    fail(LIST + " line " + number + ": an entry starts with \"21 \" or \"8 \": " + line);
                } else if (!sections.get(section).add(line)) {
                    fail(LIST + " line " + number + ": listed twice: " + line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sections;
    }
}
