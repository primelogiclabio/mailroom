package io.primelogiclab.mailroom.test;

import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.util.List;

/** Capabilities shared by the test transports. */
final class Defaults {

    private Defaults() {
    }

    /** Everything a typical provider supports, without batch sending. */
    static TransportCapabilities.Builder capabilities() {
        return TransportCapabilities.builder().supports(Capability.HTML, Capability.TEXT, Capability.ATTACHMENTS,
                Capability.INLINE_IMAGES, Capability.CUSTOM_HEADERS, Capability.TAGS, Capability.METADATA,
                Capability.SCHEDULING, Capability.IDEMPOTENCY, Capability.MESSAGE_ID);
    }

    /** The explicit capabilities, or the defaults, plus any extra understood extension classes. */
    @SuppressWarnings("unchecked")
    static TransportCapabilities build(TransportCapabilities explicit,
            List<Class<? extends EmailExtension>> understood) {
        TransportCapabilities.Builder b;
        if (explicit == null) {
            b = capabilities();
        } else {
            b = TransportCapabilities.builder()
                    .supports(explicit.supported().toArray(new Capability[0]));
            for (Class<? extends EmailExtension> type : explicit.understood()) {
                b.understands(type);
            }
            if (explicit.maxRecipientsPerMessage().isPresent()) {
                b.maxRecipientsPerMessage(explicit.maxRecipientsPerMessage().getAsInt());
            }
            if (explicit.maxMessageSize().isPresent()) {
                b.maxMessageSize(explicit.maxMessageSize().getAsLong());
            }
        }
        for (Class<? extends EmailExtension> type : understood) {
            b.understands(type);
        }
        return b.build();
    }
}
