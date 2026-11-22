package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.MailroomException;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpEnginesTest {

    @TempDir
    Path temp;

    /** An engine as an engine module would register it. */
    public static final class FakeEngine implements HttpEngine {
        static final AtomicInteger CLOSED = new AtomicInteger();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            CLOSED.incrementAndGet();
        }
    }

    /** A second engine module. */
    public static final class OtherEngine implements HttpEngine {
        @Override
        public String name() {
            return "other";
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private ClassLoader loaderListing(String... classNames) throws IOException {
        Path services = temp.resolve("META-INF/services");
        Files.createDirectories(services);
        StringBuilder lines = new StringBuilder("# test engines\n");
        for (String name : classNames) {
            lines.append(name).append('\n');
        }
        Files.write(services.resolve(HttpEngine.class.getName()), lines.toString().getBytes(StandardCharsets.UTF_8));
        File dir = temp.toFile();
        return new URLClassLoader(new URL[] {dir.toURI().toURL()}, getClass().getClassLoader());
    }

    @Test
    void fallsBackToUrlConnectionWhenNoEngineIsRegistered() throws IOException {
        assertThat(HttpEngines.load().name()).isEqualTo("urlconnection");
        assertThat(HttpEngines.load(loaderListing()).name()).isEqualTo("urlconnection");
        assertThat(HttpEngines.load()).isInstanceOf(UrlConnectionEngine.class);
    }

    @Test
    void usesTheRegisteredEngine() throws IOException {
        HttpEngine engine = HttpEngines.load(loaderListing(FakeEngine.class.getName()));
        assertThat(engine).isInstanceOf(FakeEngine.class);
        assertThat(engine.name()).isEqualTo("fake");
        assertThat(HttpEngines.load(loaderListing(FakeEngine.class.getName()))).isNotSameAs(engine);
    }

    @Test
    void twoRegisteredEnginesFailAndAreClosed() throws IOException {
        int closedBefore = FakeEngine.CLOSED.get();
        ClassLoader loader = loaderListing(FakeEngine.class.getName(), OtherEngine.class.getName());

        assertThatThrownBy(() -> HttpEngines.load(loader))
                .isInstanceOf(MailroomException.class)
                .hasMessage("more than one HttpEngine is registered: fake (" + FakeEngine.class.getName()
                        + "), other (" + OtherEngine.class.getName() + "); keep one engine module on the class path");
        assertThat(FakeEngine.CLOSED.get()).isEqualTo(closedBefore + 1);
    }

    @Test
    void aBrokenServicesEntryFailsClearly() throws IOException {
        ClassLoader loader = loaderListing("com.example.MissingEngine");
        assertThatThrownBy(() -> HttpEngines.load(loader))
                .isInstanceOf(MailroomException.class)
                .hasMessageContaining("A mailroom HttpEngine could not be loaded")
                .hasMessageContaining("com.example.MissingEngine");
    }
}
