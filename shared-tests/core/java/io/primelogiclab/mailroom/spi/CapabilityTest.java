package io.primelogiclab.mailroom.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilityTest {

    @Test
    void wellKnownConstantsHaveStableKeys() {
        assertThat(Capability.HTML.key()).isEqualTo("html");
        assertThat(Capability.TEXT.key()).isEqualTo("text");
        assertThat(Capability.ATTACHMENTS.key()).isEqualTo("attachments");
        assertThat(Capability.INLINE_IMAGES.key()).isEqualTo("inline-images");
        assertThat(Capability.CUSTOM_HEADERS.key()).isEqualTo("custom-headers");
        assertThat(Capability.TAGS.key()).isEqualTo("tags");
        assertThat(Capability.METADATA.key()).isEqualTo("metadata");
        assertThat(Capability.SCHEDULING.key()).isEqualTo("scheduling");
        assertThat(Capability.IDEMPOTENCY.key()).isEqualTo("idempotency");
        assertThat(Capability.BATCH.key()).isEqualTo("batch");
        assertThat(Capability.MESSAGE_ID.key()).isEqualTo("message-id");
    }

    @Test
    void ofAWellKnownKeyEqualsTheConstant() {
        assertThat(Capability.of("html")).isEqualTo(Capability.HTML).hasSameHashCodeAs(Capability.HTML);
        assertThat(Capability.of("inline-images")).isEqualTo(Capability.INLINE_IMAGES);
        assertThat(Capability.of("html")).isNotEqualTo(Capability.TEXT);
    }

    @Test
    void providersCanAddNamespacedCapabilities() {
        Capability pools = Capability.of("sendgrid.ip-pools");
        assertThat(pools.key()).isEqualTo("sendgrid.ip-pools");
        assertThat(pools.toString()).isEqualTo("sendgrid.ip-pools");
        assertThat(Capability.of("m365.save_to_sent")).isEqualTo(Capability.of("m365.save_to_sent"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTML", "inline images", ".html", "html.", "html..x", "html-", "émoji", "a/b"})
    void rejectsKeysThatAreNotLowerCaseTokens(String key) {
        assertThatThrownBy(() -> Capability.of(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysLongerThan64() {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            key.append('a');
        }
        assertThatThrownBy(() -> Capability.of(key.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Capability.of(key.substring(1)).key()).hasSize(64);
    }
}
