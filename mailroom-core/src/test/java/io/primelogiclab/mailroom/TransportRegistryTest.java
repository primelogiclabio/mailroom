package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.internal.CoreVersion;
import io.primelogiclab.mailroom.spi.ConfigKey;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.spi.TransportConfig;
import io.primelogiclab.mailroom.spi.TransportProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TransportRegistryTest {

    /** A transport that only remembers how it was configured. */
    public static final class RecordingTransport implements MailTransport {
        final String name;
        final String providerId;
        final TransportConfig config;
        boolean closed;

        RecordingTransport(String name, String providerId, TransportConfig config) {
            this.name = name;
            this.providerId = providerId;
            this.config = config;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public TransportCapabilities capabilities() {
            return TransportCapabilities.builder().build();
        }

        @Override
        public SendResult send(Email email, SendContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Needs host; port optional. */
    public static final class SmtpLikeProvider implements TransportProvider {
        @Override
        public String id() {
            return "smtp";
        }

        @Override
        public Set<ConfigKey> configKeys() {
            return new LinkedHashSet<ConfigKey>(Arrays.asList(ConfigKey.of("host").required(), ConfigKey.of("port"),
                    ConfigKey.of("oauth.client-id")));
        }

        @Override
        public MailTransport create(String name, TransportConfig config) {
            return new RecordingTransport(name, id(), config);
        }
    }

    /** Needs a secret api-key. */
    public static final class ApiLikeProvider implements TransportProvider {
        @Override
        public String id() {
            return "smtp2go";
        }

        @Override
        public Set<ConfigKey> configKeys() {
            return Collections.singleton(ConfigKey.of("api-key").required().secret());
        }

        @Override
        public MailTransport create(String name, TransportConfig config) {
            return new RecordingTransport(name, id(), config);
        }
    }

    /** Built against a core from the future. */
    public static final class FutureProvider implements TransportProvider {
        @Override
        public String id() {
            return "future";
        }

        @Override
        public String requiresCore() {
            return "99.0";
        }

        @Override
        public MailTransport create(String name, TransportConfig config) {
            throw new UnsupportedOperationException();
        }
    }

    /** Same id as SmtpLikeProvider. */
    public static final class OtherSmtpProvider implements TransportProvider {
        @Override
        public String id() {
            return "smtp";
        }

        @Override
        public MailTransport create(String name, TransportConfig config) {
            throw new UnsupportedOperationException();
        }
    }

    /** Declares a requiresCore that isn't a version. */
    public static final class SloppyProvider implements TransportProvider {
        @Override
        public String id() {
            return "sloppy";
        }

        @Override
        public String requiresCore() {
            return "latest";
        }

        @Override
        public MailTransport create(String name, TransportConfig config) {
            throw new UnsupportedOperationException();
        }
    }

    @TempDir
    Path temp;

    /** A class loader whose only services file lists the given provider classes. */
    private ClassLoader loaderListing(String... classNames) throws IOException {
        Path services = temp.resolve("META-INF/services");
        Files.createDirectories(services);
        StringBuilder lines = new StringBuilder("# test providers\n");
        for (String name : classNames) {
            lines.append(name).append('\n');
        }
        Files.write(services.resolve(TransportProvider.class.getName()), lines.toString().getBytes(StandardCharsets.UTF_8));
        File dir = temp.toFile();
        return new URLClassLoader(new URL[] {dir.toURI().toURL()}, getClass().getClassLoader());
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void discoversProvidersThroughServiceLoader() throws IOException {
        TransportRegistry registry = TransportRegistry.discover(
                loaderListing(SmtpLikeProvider.class.getName(), ApiLikeProvider.class.getName()));
        assertThat(registry.providerIds()).containsExactly("smtp", "smtp2go");
        assertThat(registry.provider("smtp")).containsInstanceOf(SmtpLikeProvider.class);
        assertThat(registry.provider("sendgrid")).isEmpty();
        assertThat(registry.toString()).isEqualTo("TransportRegistry[smtp, smtp2go]");
    }

    @Test
    void theDefaultClassLoaderFindsNothingOnThisClasspath() {
        assertThat(TransportRegistry.discover().providerIds()).isEmpty();
    }

    /** The CORE-5 done-when. */
    @Test
    void aProviderNeedingANewerCoreFailsAtDiscovery() throws IOException {
        ClassLoader loader = loaderListing(SmtpLikeProvider.class.getName(), FutureProvider.class.getName());
        assertThatThrownBy(() -> TransportRegistry.discover(loader))
                .isInstanceOf(MailroomException.class)
                .hasMessage("the provider 'future' (" + FutureProvider.class.getName()
                        + ") needs mailroom-core ≥ 99.0, found " + CoreVersion.current());
        assertThatThrownBy(() -> TransportRegistry.of(new FutureProvider()))
                .hasMessageContaining("needs mailroom-core ≥ 99.0");
    }

    @Test
    void theMessageNamesTheProviderJarWhenItsManifestDoes() {
        assertThat(TransportRegistry.incompatible("mailroom-resend", "1.3.0", "resend", "x.ResendProvider", "1.2",
                "1.1.0")).isEqualTo("mailroom-resend 1.3.0 needs mailroom-core ≥ 1.2, found 1.1.0");
        assertThat(TransportRegistry.incompatible("mailroom-resend", null, "resend", "x.ResendProvider", "1.2",
                "1.1.0")).isEqualTo("mailroom-resend needs mailroom-core ≥ 1.2, found 1.1.0");
    }

    @Test
    void rejectsDuplicateIdsAndUnreadableRequirements() {
        assertThatThrownBy(() -> TransportRegistry.of(new SmtpLikeProvider(), new OtherSmtpProvider()))
                .hasMessage("two providers have the id 'smtp': " + SmtpLikeProvider.class.getName() + " and "
                        + OtherSmtpProvider.class.getName());
        assertThatThrownBy(() -> TransportRegistry.of(new SloppyProvider()))
                .hasMessageContaining("declares requiresCore() 'latest', which is not a version");
    }

    @Test
    void aBrokenServicesEntryFailsClearly() throws IOException {
        ClassLoader loader = loaderListing("com.example.MissingProvider");
        assertThatThrownBy(() -> TransportRegistry.discover(loader))
                .isInstanceOf(MailroomException.class)
                .hasMessageContaining("A mailroom TransportProvider could not be loaded")
                .hasMessageContaining("com.example.MissingProvider");
    }

    @Test
    void createsEveryConfiguredTransport() {
        TransportRegistry registry = TransportRegistry.of(new SmtpLikeProvider(), new ApiLikeProvider());
        Map<String, MailTransport> transports = registry.createAll(props(
                "default-from", "Shop <shop@example.com>",
                "transports.primary.type", "smtp2go",
                "transports.primary.api-key", "k-123",
                "transports.backup.type", " smtp ",
                "transports.backup.host", "smtp.example.com",
                "transports.backup.oauth.client-id", "abc",
                "routing.default", "primary,backup"));
        assertThat(transports.keySet()).containsExactly("backup", "primary");
        RecordingTransport backup = (RecordingTransport) transports.get("backup");
        assertThat(backup.providerId).isEqualTo("smtp");
        assertThat(backup.config.require("host")).isEqualTo("smtp.example.com");
        assertThat(backup.config.get("oauth.client-id")).contains("abc");
        RecordingTransport primary = (RecordingTransport) transports.get("primary");
        assertThat(primary.config.secret("api-key").get().reveal()).isEqualTo("k-123");
        assertThatThrownBy(() -> transports.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsEveryProblemAcrossTransportsAtOnce() {
        TransportRegistry registry = TransportRegistry.of(new SmtpLikeProvider(), new ApiLikeProvider());
        assertThatThrownBy(() -> registry.createAll(props(
                "transports.a.host", "x",
                "transports.b.type", "sendgird",
                "transports.c.type", "smtp2go",
                "transports.c.api_key", "secret-value-123",
                "transports.d", "oops",
                "transports.e!.type", "smtp")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> {
                    assertThat(e.problems()).containsExactly(
                            "'transports.d' is not of the form transports.<name>.<key>",
                            "the transport name 'e!' must be letters, digits, '_' or '-'",
                            "transport 'a' has no type (set transports.a.type)",
                            "transport 'b': no provider for type 'sendgird' (found: smtp, smtp2go)",
                            "transport 'c': unknown key 'api_key' (known keys: api-key)",
                            "transport 'c': 'api-key' is required");
                    assertThat(e.getMessage()).doesNotContain("secret-value-123");
                });
    }

    @Test
    void closesTransportsAlreadyCreatedWhenAnotherFails() {
        final RecordingTransport[] made = new RecordingTransport[1];
        TransportProvider remembering = new TransportProvider() {
            @Override
            public String id() {
                return "remember";
            }

            @Override
            public MailTransport create(String name, TransportConfig config) {
                made[0] = new RecordingTransport(name, id(), config);
                return made[0];
            }
        };
        TransportRegistry registry = TransportRegistry.of(remembering);
        assertThatThrownBy(() -> registry.createAll(props("transports.a.type", "remember", "transports.b.type", "nope")))
                .isInstanceOf(InvalidConfigException.class);
        assertThat(made[0].closed).isTrue();
    }

    @Test
    void noTransportsKeysMeansNoTransports() {
        assertThat(TransportRegistry.of(new SmtpLikeProvider()).createAll(props("default-from", "a@example.com")))
                .isEmpty();
    }

    @Test
    void createBuildsOneTransport() {
        TransportRegistry registry = TransportRegistry.of(new SmtpLikeProvider());
        MailTransport t = registry.create("relay", "smtp", props("host", "relay.example.com"));
        assertThat(t.name()).isEqualTo("relay");
        assertThatThrownBy(() -> registry.create("relay", "resend", props()))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("no provider for type 'resend' (found: smtp)");
    }
}
