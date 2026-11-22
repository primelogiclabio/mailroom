package io.primelogiclab.mailroom.test;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.Status;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A transport that sends nothing: it records every email and accepts it. Use it in application tests to check what
 * your code sends, without a real provider.
 *
 * <pre>{@code
 * InMemoryTransport outbox = InMemoryTransport.named("primary");
 * // ... build the Mailroom with this transport and run the code under test ...
 * Email confirmation = outbox.assertSentOne("player@example.com", "booking");
 * }</pre>
 *
 * <p>The assertions throw {@link AssertionError}, so they work with any test framework. The transport is
 * thread-safe.
 *
 * @since 0.1.0
 */
public final class InMemoryTransport implements MailTransport {

    private final String name;
    private final String providerId;
    private final TransportCapabilities capabilities;
    private final List<Email> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger messageIds = new AtomicInteger();

    private InMemoryTransport(String name, String providerId, TransportCapabilities capabilities) {
        this.name = name;
        this.providerId = providerId;
        this.capabilities = capabilities;
    }

    /**
     * Creates a transport with provider id {@code in-memory} that supports every well-known capability except batch
     * sending.
     *
     * @param name the configured name, such as {@code primary}
     * @return the transport
     * @since 0.1.0
     */
    public static InMemoryTransport named(String name) {
        return builder(name).build();
    }

    /**
     * Starts a transport with a custom provider id or capabilities.
     *
     * @param name the configured name, such as {@code primary}
     * @return a builder
     * @since 0.1.0
     */
    public static Builder builder(String name) {
        return new Builder(name);
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
        return capabilities;
    }

    /**
     * Records the email and accepts it, with message id {@code in-memory-<n>}; scheduled when the email has a
     * {@code sendAt}.
     *
     * @param email   the email
     * @param context the send context; its clock gives the accepted time
     * @return the result
     */
    @Override
    public SendResult send(Email email, SendContext context) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(context, "context");
        sent.add(email);
        return SendResult.builder().transportName(name).providerId(providerId)
                .providerMessageId("in-memory-" + messageIds.incrementAndGet())
                .status(email.sendAt().isPresent() ? Status.SCHEDULED : Status.ACCEPTED)
                .acceptedAt(context.clock().instant()).build();
    }

    /**
     * Returns every email sent, in order.
     *
     * @return a copy of the emails
     * @since 0.1.0
     */
    public List<Email> sent() {
        return Collections.unmodifiableList(new ArrayList<>(sent));
    }

    /**
     * Returns how many emails were sent.
     *
     * @return the count
     * @since 0.1.0
     */
    public int sentCount() {
        return sent.size();
    }

    /**
     * Returns the most recent email.
     *
     * @return the last email, if any
     * @since 0.1.0
     */
    public Optional<Email> lastSent() {
        List<Email> copy = sent();
        return copy.isEmpty() ? Optional.<Email>empty() : Optional.of(copy.get(copy.size() - 1));
    }

    /**
     * Returns the emails with this recipient in {@code to}, {@code cc} or {@code bcc}.
     *
     * @param address the recipient, such as {@code player@example.com}; the domain's letter case doesn't matter
     * @return the matching emails, in order
     * @since 0.1.0
     */
    public List<Email> sentTo(String address) {
        String wanted = Address.parse(address).address();
        List<Email> matches = new ArrayList<>();
        for (Email email : sent) {
            for (Address recipient : email.recipients()) {
                if (recipient.address().equals(wanted)) {
                    matches.add(email);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(matches);
    }

    /**
     * Returns the emails carrying this tag.
     *
     * @param tag the tag, such as {@code booking}
     * @return the matching emails, in order
     * @since 0.1.0
     */
    public List<Email> sentWithTag(String tag) {
        Objects.requireNonNull(tag, "tag");
        List<Email> matches = new ArrayList<>();
        for (Email email : sent) {
            if (email.tags().contains(tag)) {
                matches.add(email);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    /**
     * Forgets every email sent so far.
     *
     * @since 0.1.0
     */
    public void clear() {
        sent.clear();
    }

    /**
     * Fails unless exactly this many emails were sent.
     *
     * @param expected the expected count
     * @throws AssertionError if the count differs
     * @since 0.1.0
     */
    public void assertSentCount(int expected) {
        int actual = sent.size();
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " email(s) sent through '" + name + "' but there were "
                    + actual);
        }
    }

    /**
     * Fails if any email was sent.
     *
     * @throws AssertionError if an email was sent
     * @since 0.1.0
     */
    public void assertNothingSent() {
        assertSentCount(0);
    }

    /**
     * Fails unless exactly one email was sent to this recipient, and returns it.
     *
     * @param to the recipient
     * @return the one email
     * @throws AssertionError if none or several were sent to the recipient
     * @since 0.1.0
     */
    public Email assertSentOne(String to) {
        return exactlyOne(sentTo(to), "to " + to);
    }

    /**
     * Fails unless exactly one email with this tag was sent to this recipient, and returns it.
     *
     * @param to  the recipient
     * @param tag the tag
     * @return the one email
     * @throws AssertionError if none or several match
     * @since 0.1.0
     */
    public Email assertSentOne(String to, String tag) {
        Objects.requireNonNull(tag, "tag");
        List<Email> matches = new ArrayList<>();
        for (Email email : sentTo(to)) {
            if (email.tags().contains(tag)) {
                matches.add(email);
            }
        }
        return exactlyOne(matches, "to " + to + " with tag '" + tag + "'");
    }

    /**
     * Describes the transport, such as {@code InMemoryTransport[primary, sent=2]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "InMemoryTransport[" + name + ", sent=" + sent.size() + "]";
    }

    private Email exactlyOne(List<Email> matches, String what) {
        if (matches.size() != 1) {
            throw new AssertionError("Expected one email " + what + " through '" + name + "' but found "
                    + matches.size() + " (of " + sent.size() + " sent)");
        }
        return matches.get(0);
    }

    /**
     * Collects an in-memory transport's settings.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final String name;
        private String providerId = "in-memory";
        private TransportCapabilities capabilities;
        private final List<Class<? extends EmailExtension>> understood =
                new ArrayList<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /**
         * Sets the provider id.
         *
         * @param providerId the id; {@code in-memory} by default
         * @return this builder
         * @since 0.1.0
         */
        public Builder providerId(String providerId) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            return this;
        }

        /**
         * Replaces the default capabilities.
         *
         * @param capabilities what the transport claims to carry
         * @return this builder
         * @since 0.1.0
         */
        public Builder capabilities(TransportCapabilities capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            return this;
        }

        /**
         * Adds extension classes the transport understands.
         *
         * @param extensionTypes the classes
         * @return this builder
         * @since 0.1.0
         */
        @SafeVarargs
        public final Builder understands(Class<? extends EmailExtension>... extensionTypes) {
            for (Class<? extends EmailExtension> type : Objects.requireNonNull(extensionTypes, "extensionTypes")) {
                understood.add(Objects.requireNonNull(type, "extensionType"));
            }
            return this;
        }

        /**
         * Builds the transport.
         *
         * @return the transport
         * @since 0.1.0
         */
        public InMemoryTransport build() {
            return new InMemoryTransport(name, providerId, Defaults.build(capabilities, understood));
        }
    }
}
