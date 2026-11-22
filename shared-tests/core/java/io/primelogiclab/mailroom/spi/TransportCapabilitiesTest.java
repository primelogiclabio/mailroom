package io.primelogiclab.mailroom.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.EmailExtension;
import org.junit.jupiter.api.Test;

class TransportCapabilitiesTest {

    static final class ResendOptions implements EmailExtension {
    }

    static final class SendGridOptions implements EmailExtension {
    }

    @Test
    void anEmptyBuilderSupportsNothing() {
        TransportCapabilities none = TransportCapabilities.builder().build();
        assertThat(none.supported()).isEmpty();
        assertThat(none.understood()).isEmpty();
        assertThat(none.supports(Capability.HTML)).isFalse();
        assertThat(none.understands(ResendOptions.class)).isFalse();
        assertThat(none.maxRecipientsPerMessage()).isEmpty();
        assertThat(none.maxMessageSize()).isEmpty();
    }

    @Test
    void declaresWhatTheTransportCanCarry() {
        TransportCapabilities caps = TransportCapabilities.builder()
                .supports(Capability.HTML, Capability.TEXT)
                .supports(Capability.ATTACHMENTS, Capability.of("resend.topics"))
                .understands(ResendOptions.class)
                .maxRecipientsPerMessage(50)
                .maxMessageSize(40L * 1024 * 1024)
                .build();
        assertThat(caps.supported()).containsExactly(
                Capability.HTML, Capability.TEXT, Capability.ATTACHMENTS, Capability.of("resend.topics"));
        assertThat(caps.supports(Capability.of("html"))).isTrue();
        assertThat(caps.supports(Capability.SCHEDULING)).isFalse();
        assertThat(caps.understands(ResendOptions.class)).isTrue();
        assertThat(caps.understands(SendGridOptions.class)).isFalse();
        assertThat(caps.maxRecipientsPerMessage()).hasValue(50);
        assertThat(caps.maxMessageSize()).hasValue(40L * 1024 * 1024);
        assertThat(caps.toString()).isEqualTo("TransportCapabilities[supports=[html, text, attachments, resend.topics],"
                + " understands=[ResendOptions], maxRecipientsPerMessage=50, maxMessageSize=41943040]");
    }

    @Test
    void isImmutableAfterBuild() {
        TransportCapabilities.Builder builder = TransportCapabilities.builder().supports(Capability.HTML);
        TransportCapabilities caps = builder.build();
        builder.supports(Capability.TEXT).understands(ResendOptions.class).maxRecipientsPerMessage(5);
        assertThat(caps.supported()).containsExactly(Capability.HTML);
        assertThat(caps.understood()).isEmpty();
        assertThat(caps.maxRecipientsPerMessage()).isEmpty();
        assertThatThrownBy(() -> caps.supported().add(Capability.TEXT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsLimitsBelowOne() {
        assertThatThrownBy(() -> TransportCapabilities.builder().maxRecipientsPerMessage(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransportCapabilities.builder().maxMessageSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityComparesEverything() {
        TransportCapabilities a = TransportCapabilities.builder().supports(Capability.HTML)
                .understands(ResendOptions.class).maxRecipientsPerMessage(50).build();
        TransportCapabilities b = TransportCapabilities.builder().supports(Capability.of("html"))
                .understands(ResendOptions.class).maxRecipientsPerMessage(50).build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(TransportCapabilities.builder().supports(Capability.HTML).build());
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> TransportCapabilities.builder().supports((Capability) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransportCapabilities.builder().build().supports(null))
                .isInstanceOf(NullPointerException.class);
    }
}
