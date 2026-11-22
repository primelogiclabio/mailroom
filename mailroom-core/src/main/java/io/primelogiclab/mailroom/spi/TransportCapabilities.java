package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.EmailExtension;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a transport can carry: the {@link Capability capabilities} it supports, the {@link EmailExtension} classes it
 * understands, and the limits its provider documents. The router uses it to drop transports that can't carry a
 * message before trying any.
 *
 * <pre>{@code
 * TransportCapabilities.builder()
 *     .supports(Capability.HTML, Capability.TEXT, Capability.ATTACHMENTS, Capability.MESSAGE_ID)
 *     .understands(ResendOptions.class)
 *     .maxRecipientsPerMessage(50)
 *     .build();
 * }</pre>
 *
 * <p>Set a limit only where the provider documents one. Limits change, so each transport keeps its numbers in one
 * place.
 *
 * @param supported               every supported capability, in the order they were declared; the set cannot be
 *                                changed
 * @param understood              every understood extension class, in the order they were declared; the set cannot be
 *                                changed
 * @param maxRecipientsPerMessage the most recipients (to, cc and bcc together) the provider takes in one message, if
 *                                it documents a limit
 * @param maxMessageSize          the largest message the provider accepts, in bytes, if it documents a limit
 * @since 0.1.0
 */
public record TransportCapabilities(Set<Capability> supported, Set<Class<? extends EmailExtension>> understood,
        OptionalInt maxRecipientsPerMessage, OptionalLong maxMessageSize) {

    /**
     * Checks the limits and copies the sets, keeping their order.
     *
     * @throws IllegalArgumentException if a limit is below 1
     * @since 0.1.0
     */
    public TransportCapabilities {
        supported = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(supported, "supported")));
        understood = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(understood, "understood")));
        supported.forEach(capability -> Objects.requireNonNull(capability, "capability"));
        understood.forEach(type -> Objects.requireNonNull(type, "extensionType"));
        Objects.requireNonNull(maxRecipientsPerMessage, "maxRecipientsPerMessage");
        Objects.requireNonNull(maxMessageSize, "maxMessageSize");
        if (maxRecipientsPerMessage.isPresent() && maxRecipientsPerMessage.getAsInt() < 1) {
            throw new IllegalArgumentException("maxRecipientsPerMessage must be at least 1");
        }
        if (maxMessageSize.isPresent() && maxMessageSize.getAsLong() < 1) {
            throw new IllegalArgumentException("maxMessageSize must be at least 1");
        }
    }

    /**
     * Starts an empty set of capabilities.
     *
     * @return a builder that supports nothing yet
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Tells whether the transport supports a capability.
     *
     * @param capability the capability
     * @return true if supported
     * @since 0.1.0
     */
    public boolean supports(Capability capability) {
        return supported.contains(Objects.requireNonNull(capability, "capability"));
    }

    /**
     * Tells whether the transport understands an extension class, so it can carry emails where that extension is
     * essential.
     *
     * @param extensionType the extension class
     * @return true if understood
     * @since 0.1.0
     */
    public boolean understands(Class<? extends EmailExtension> extensionType) {
        return understood.contains(Objects.requireNonNull(extensionType, "extensionType"));
    }

    /**
     * Describes the capabilities, such as
     * {@code TransportCapabilities[supports=[html, text], understands=[ResendOptions], maxRecipientsPerMessage=50]}.
     *
     * @return a readable description
     */
    @Override
    public String toString() {
        var s = new StringBuilder("TransportCapabilities[supports=").append(supported).append(", understands=[")
                .append(understood.stream().map(Class::getSimpleName).collect(Collectors.joining(", "))).append(']');
        maxRecipientsPerMessage.ifPresent(max -> s.append(", maxRecipientsPerMessage=").append(max));
        maxMessageSize.ifPresent(max -> s.append(", maxMessageSize=").append(max));
        return s.append(']').toString();
    }

    /**
     * Collects a transport's capabilities. Each call adds to what is already declared.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Set<Capability> supported = new LinkedHashSet<>();
        private final Set<Class<? extends EmailExtension>> understood = new LinkedHashSet<>();
        private OptionalInt maxRecipientsPerMessage = OptionalInt.empty();
        private OptionalLong maxMessageSize = OptionalLong.empty();

        private Builder() {
        }

        /**
         * Declares supported capabilities.
         *
         * @param capabilities the capabilities
         * @return this builder
         * @since 0.1.0
         */
        public Builder supports(Capability... capabilities) {
            for (var capability : Objects.requireNonNull(capabilities, "capabilities")) {
                supported.add(Objects.requireNonNull(capability, "capability"));
            }
            return this;
        }

        /**
         * Declares extension classes the transport understands.
         *
         * @param extensionTypes the classes, such as {@code SendGridOptions.class}
         * @return this builder
         * @since 0.1.0
         */
        @SafeVarargs
        public final Builder understands(Class<? extends EmailExtension>... extensionTypes) {
            for (var type : Objects.requireNonNull(extensionTypes, "extensionTypes")) {
                understood.add(Objects.requireNonNull(type, "extensionType"));
            }
            return this;
        }

        /**
         * Sets the most recipients the provider takes in one message.
         *
         * @param max the limit, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code max} is below 1
         * @since 0.1.0
         */
        public Builder maxRecipientsPerMessage(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxRecipientsPerMessage must be at least 1");
            }
            this.maxRecipientsPerMessage = OptionalInt.of(max);
            return this;
        }

        /**
         * Sets the largest message the provider accepts.
         *
         * @param bytes the limit in bytes, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code bytes} is below 1
         * @since 0.1.0
         */
        public Builder maxMessageSize(long bytes) {
            if (bytes < 1) {
                throw new IllegalArgumentException("maxMessageSize must be at least 1");
            }
            this.maxMessageSize = OptionalLong.of(bytes);
            return this;
        }

        /**
         * Builds the capabilities.
         *
         * @return the capabilities; later changes to this builder don't affect them
         * @since 0.1.0
         */
        public TransportCapabilities build() {
            return new TransportCapabilities(supported, understood, maxRecipientsPerMessage, maxMessageSize);
        }
    }
}
