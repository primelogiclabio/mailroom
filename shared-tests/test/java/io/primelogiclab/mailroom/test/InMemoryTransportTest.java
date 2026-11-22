package io.primelogiclab.mailroom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.Status;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryTransportTest {

    static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    static final SendContext CONTEXT = SendContext.builder().clock(Clock.fixed(NOW, ZoneOffset.UTC)).build();

    static final class TemplateOptions implements EmailExtension {
    }

    static Email email(String to, String... tags) {
        return Email.builder().to(to).subject("Hi").text("Hello").tag(tags).build();
    }

    @Test
    void recordsAndAcceptsEveryEmail() {
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        SendResult first = outbox.send(email("a@example.com"), CONTEXT);
        SendResult second = outbox.send(email("b@example.com"), CONTEXT);
        assertThat(first.providerMessageId()).contains("in-memory-1");
        assertThat(second.providerMessageId()).contains("in-memory-2");
        assertThat(first.transportName()).isEqualTo("primary");
        assertThat(first.providerId()).isEqualTo("in-memory");
        assertThat(first.status()).isEqualTo(Status.ACCEPTED);
        assertThat(first.acceptedAt()).isEqualTo(NOW);
        assertThat(outbox.sent()).extracting(e -> e.to().get(0).address()).containsExactly("a@example.com", "b@example.com");
        assertThat(outbox.sentCount()).isEqualTo(2);
        assertThat(outbox.lastSent()).contains(outbox.sent().get(1));
        assertThat(outbox.toString()).isEqualTo("InMemoryTransport[primary, sent=2]");
    }

    @Test
    void scheduledEmailsAreScheduled() {
        Email later = Email.builder().to("a@example.com").subject("s").text("t").sendAt(NOW.plusSeconds(60)).build();
        assertThat(InMemoryTransport.named("t").send(later, CONTEXT).status()).isEqualTo(Status.SCHEDULED);
    }

    @Test
    void findsEmailsByRecipientAndTag() {
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        outbox.send(email("player@example.com", "booking"), CONTEXT);
        outbox.send(Email.builder().to("x@example.com").cc("player@EXAMPLE.com").subject("s").text("t").build(),
                CONTEXT);
        outbox.send(email("other@example.com", "booking"), CONTEXT);
        assertThat(outbox.sentTo("player@example.com")).hasSize(2);
        assertThat(outbox.sentTo("Player <player@Example.COM>")).hasSize(2);
        assertThat(outbox.sentTo("Player@example.com")).as("the local part is case-sensitive").isEmpty();
        assertThat(outbox.sentWithTag("booking")).hasSize(2);
        assertThat(outbox.sentTo("nobody@example.com")).isEmpty();
    }

    @Test
    void assertionsPassAndReturnTheEmail() {
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        outbox.assertNothingSent();
        Email booking = email("player@example.com", "booking");
        outbox.send(booking, CONTEXT);
        outbox.send(email("player@example.com", "reminder"), CONTEXT);
        outbox.assertSentCount(2);
        assertThat(outbox.assertSentOne("player@example.com", "booking")).isSameAs(booking);
    }

    @Test
    void assertionsFailWithClearMessages() {
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        outbox.send(email("player@example.com", "booking"), CONTEXT);
        outbox.send(email("player@example.com", "booking"), CONTEXT);
        assertThatThrownBy(() -> outbox.assertSentCount(1)).isInstanceOf(AssertionError.class)
                .hasMessage("Expected 1 email(s) sent through 'primary' but there were 2");
        assertThatThrownBy(outbox::assertNothingSent).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> outbox.assertSentOne("player@example.com")).isInstanceOf(AssertionError.class)
                .hasMessage("Expected one email to player@example.com through 'primary' but found 2 (of 2 sent)");
        assertThatThrownBy(() -> outbox.assertSentOne("player@example.com", "auth")).isInstanceOf(AssertionError.class)
                .hasMessage("Expected one email to player@example.com with tag 'auth' through 'primary' but found 0"
                        + " (of 2 sent)");
    }

    @Test
    void clearForgetsTheEmails() {
        InMemoryTransport outbox = InMemoryTransport.named("primary");
        outbox.send(email("a@example.com"), CONTEXT);
        outbox.clear();
        outbox.assertNothingSent();
        assertThat(outbox.lastSent()).isEmpty();
    }

    @Test
    void hasDefaultCapabilitiesThatCanBeChanged() {
        TransportCapabilities defaults = InMemoryTransport.named("t").capabilities();
        assertThat(defaults.supports(Capability.HTML)).isTrue();
        assertThat(defaults.supports(Capability.SCHEDULING)).isTrue();
        assertThat(defaults.supports(Capability.BATCH)).isFalse();

        InMemoryTransport custom = InMemoryTransport.builder("t").providerId("fake-sendgrid")
                .capabilities(TransportCapabilities.builder().supports(Capability.TEXT).maxRecipientsPerMessage(5)
                        .build())
                .understands(TemplateOptions.class).build();
        assertThat(custom.providerId()).isEqualTo("fake-sendgrid");
        assertThat(custom.capabilities().supported()).containsExactly(Capability.TEXT);
        assertThat(custom.capabilities().maxRecipientsPerMessage()).hasValue(5);
        assertThat(custom.capabilities().understands(TemplateOptions.class)).isTrue();
        assertThat(InMemoryTransport.builder("t").understands(TemplateOptions.class).build().capabilities()
                .supports(Capability.HTML)).isTrue();
    }

    @Test
    void isSafeToUseFromManyThreads() throws Exception {
        final InMemoryTransport outbox = InMemoryTransport.named("primary");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<SendResult>> results = new ArrayList<Future<SendResult>>();
            for (int i = 0; i < 200; i++) {
                final int n = i;
                results.add(pool.submit(() -> outbox.send(email("user" + n + "@example.com"), CONTEXT)));
            }
            for (Future<SendResult> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        outbox.assertSentCount(200);
        assertThat(outbox.sent()).extracting(e -> e.to().get(0).address()).doesNotHaveDuplicates();
    }

    @Test
    void compiledForJava8() throws Exception {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                InMemoryTransport.class.getResourceAsStream("InMemoryTransport.class"))) {
            in.readInt();
            in.readUnsignedShort();
            assertThat(in.readUnsignedShort()).as("class file major version").isEqualTo(52);
        }
    }
}
