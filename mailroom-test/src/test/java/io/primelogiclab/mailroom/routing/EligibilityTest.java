package io.primelogiclab.mailroom.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.internal.Router;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EligibilityTest {

    private static InMemoryTransport supporting(String name, Capability... capabilities) {
        return InMemoryTransport.builder(name)
                .capabilities(TransportCapabilities.builder().supports(capabilities).build()).build();
    }

    private static String skipReason(InMemoryTransport transport, Email email) {
        try {
            Router.builder().transport(transport).build().send(email);
        } catch (NoEligibleTransportException e) {
            return e.skipReasons().get(transport.name());
        }
        throw new AssertionError("expected the transport to be skipped");
    }

    private static Email.Builder base() {
        return Email.builder().to("player@example.com").subject("Hi");
    }

    @Test
    void needsEachBodyTypeTheEmailHas() {
        InMemoryTransport textOnly = supporting("t", Capability.TEXT);
        assertThat(skipReason(textOnly, base().text("t").html("<p>h</p>").build())).isEqualTo("doesn't support html");
        assertThat(skipReason(supporting("h", Capability.HTML), base().text("t").build()))
                .isEqualTo("doesn't support text");
        Router.builder().transport(textOnly).build().send(base().text("t").build());
        textOnly.assertSentCount(1);
    }

    @Test
    void needsAttachmentInlineHeaderAndSchedulingSupport() {
        Email everything = base().text("t")
                .attach(Attachment.of("a.pdf", "application/pdf", new byte[] {1}))
                .attach(Attachment.inline("logo", "image/png", new byte[] {2}))
                .header("List-Unsubscribe", "<mailto:u@example.com>")
                .sendAt(Instant.parse("2026-12-01T09:00:00Z")).build();
        assertThat(skipReason(supporting("t", Capability.TEXT), everything))
                .isEqualTo("doesn't support attachments, inline-images, custom-headers, scheduling");
        InMemoryTransport all = supporting("all", Capability.TEXT, Capability.ATTACHMENTS, Capability.INLINE_IMAGES,
                Capability.CUSTOM_HEADERS, Capability.SCHEDULING);
        Router.builder().transport(all).build().send(everything);
        all.assertSentCount(1);
    }

    @Test
    void tagsMetadataAndIdempotencyAreBestEffort() {
        InMemoryTransport plain = supporting("plain", Capability.TEXT);
        Router.builder().transport(plain).build()
                .send(base().text("t").tag("booking").metadata("id", "1").idempotencyKey("k-1").build());
        plain.assertSentCount(1);
    }

    @Test
    void essentialExtensionsMustBeUnderstoodAndTemplatesNeedNoBodySupport() {
        Email template = Email.builder().to("player@example.com").with(new Routing.SendGridOptions()).build();
        assertThat(skipReason(supporting("smtp2go"), template))
                .isEqualTo("doesn't understand SendGridOptions (essential)");
        InMemoryTransport sendgrid = InMemoryTransport.builder("sendgrid")
                .capabilities(TransportCapabilities.builder().build())
                .understands(Routing.SendGridOptions.class).build();
        Router.builder().transport(sendgrid).build().send(template);
        sendgrid.assertSentCount(1);
    }

    @Test
    void respectsRecipientAndSizeLimits() {
        InMemoryTransport small = InMemoryTransport.builder("small").capabilities(TransportCapabilities.builder()
                .supports(Capability.TEXT, Capability.ATTACHMENTS).maxRecipientsPerMessage(2).maxMessageSize(100)
                .build()).build();
        assertThat(skipReason(small, base().text("t").cc("a@example.com", "b@example.com").build()))
                .isEqualTo("has 3 recipients, over its limit of 2");
        assertThat(skipReason(small, base().text("t").attach(Attachment.of("a.bin", "application/octet-stream",
                new byte[150])).build())).isEqualTo("is about 151 bytes, over its limit of 100");
    }

    @Test
    void usesTheTransportsOwnPrecheck() {
        io.primelogiclab.mailroom.test.ScriptedTransport picky = io.primelogiclab.mailroom.test.ScriptedTransport
                .builder("picky").rejectPrecheck("header too long for player@example.com").build();
        assertThatThrownBy(() -> Router.builder().transport(picky).build().send(Routing.email()))
                .isInstanceOfSatisfying(NoEligibleTransportException.class, e -> assertThat(e.skipReasons())
                        .containsEntry("picky", "header too long for p***@example.com"));
        picky.assertCalls(0);
    }

    @Test
    void skipsIneligibleTransportsAndUsesTheNextOne() {
        InMemoryTransport textOnly = supporting("text-only", Capability.TEXT);
        InMemoryTransport full = InMemoryTransport.named("full");
        Router router = Router.builder().transport(textOnly).transport(full).build();
        assertThat(router.send(base().text("t").html("<p>h</p>").build()).transportName()).isEqualTo("full");
        textOnly.assertNothingSent();
    }

    @Test
    void listsEverySkipReasonInRouteOrder() {
        Router router = Router.builder().transport(supporting("a", Capability.TEXT))
                .transport(supporting("b", Capability.HTML)).transport(supporting("c")).build();
        assertThatThrownBy(() -> router.send(base().text("t").html("<p>h</p>").build()))
                .isInstanceOfSatisfying(NoEligibleTransportException.class, e -> assertThat(e.skipReasons())
                        .containsExactly(
                                new java.util.AbstractMap.SimpleEntry<String, String>("a", "doesn't support html"),
                                new java.util.AbstractMap.SimpleEntry<String, String>("b", "doesn't support text"),
                                new java.util.AbstractMap.SimpleEntry<String, String>("c",
                                        "doesn't support html, text")));
    }
}
