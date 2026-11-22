package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SecretTest {

    @Test
    void toStringIsRedacted() {
        assertThat(Secret.of("k-123").toString()).isEqualTo("Secret[******]");
        assertThat(Secret.env("ANYTHING").toString()).isEqualTo("Secret[******]");
        assertThat(String.valueOf(Secret.of("k-123"))).doesNotContain("k-123");
    }

    @Test
    void revealsALiteral() {
        assertThat(Secret.of("k-123").reveal()).isEqualTo("k-123");
    }

    @Test
    void readsASupplierOnEveryReveal() {
        final AtomicInteger version = new AtomicInteger();
        Secret rotating = Secret.of(new Supplier<String>() {
            @Override
            public String get() {
                return "key-v" + version.incrementAndGet();
            }
        });
        assertThat(rotating.reveal()).isEqualTo("key-v1");
        assertThat(rotating.reveal()).isEqualTo("key-v2");
    }

    @Test
    void readsAnEnvironmentVariableOnEveryReveal() {
        final Map<String, String> env = new HashMap<String, String>();
        Secret secret = Secret.env("SENDGRID_API_KEY", new Function<String, String>() {
            @Override
            public String apply(String name) {
                return env.get(name);
            }
        });
        assertThatThrownBy(secret::reveal).isInstanceOf(IllegalStateException.class)
                .hasMessage("The environment variable SENDGRID_API_KEY is not set");
        env.put("SENDGRID_API_KEY", "sg-1");
        assertThat(secret.reveal()).isEqualTo("sg-1");
        env.put("SENDGRID_API_KEY", "sg-2");
        assertThat(secret.reveal()).isEqualTo("sg-2");
    }

    @Test
    void aSupplierReturningNullFailsWithoutAValue() {
        Secret empty = Secret.of(new Supplier<String>() {
            @Override
            public String get() {
                return null;
            }
        });
        assertThatThrownBy(empty::reveal).isInstanceOf(IllegalStateException.class)
                .hasMessage("The secret's supplier returned no value");
    }

    @Test
    void secretsAreEqualOnlyToThemselves() {
        Secret a = Secret.of("same");
        assertThat(a).isEqualTo(a).isNotEqualTo(Secret.of("same"));
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> Secret.of((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Secret.of((Supplier<String>) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Secret.env(null)).isInstanceOf(NullPointerException.class);
    }
}
