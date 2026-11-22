package io.primelogiclab.mailroom.spi;

import io.primelogiclab.mailroom.EmailExtension;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

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
 * @since 0.1.0
 */
public final class TransportCapabilities {

    private final Set<Capability> supported;
    private final Set<Class<? extends EmailExtension>> understood;
    private final Integer maxRecipientsPerMessage;
    private final Long maxMessageSize;

    private TransportCapabilities(Builder b) {
        this.supported = Collections.unmodifiableSet(new LinkedHashSet<Capability>(b.supported));
        this.understood = Collections.unmodifiableSet(new LinkedHashSet<Class<? extends EmailExtension>>(b.understood));
        this.maxRecipientsPerMessage = b.maxRecipientsPerMessage;
        this.maxMessageSize = b.maxMessageSize;
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
     * Returns every supported capability, in the order they were declared.
     *
     * @return the capabilities; the set cannot be changed
     * @since 0.1.0
     */
    public Set<Capability> supported() {
        return supported;
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
     * Returns every understood extension class, in the order they were declared.
     *
     * @return the classes; the set cannot be changed
     * @since 0.1.0
     */
    public Set<Class<? extends EmailExtension>> understood() {
        return understood;
    }

    /**
     * Returns the most recipients (to, cc and bcc together) the provider takes in one message, if it documents a
     * limit.
     *
     * @return the limit
     * @since 0.1.0
     */
    public OptionalInt maxRecipientsPerMessage() {
        return maxRecipientsPerMessage == null ? OptionalInt.empty() : OptionalInt.of(maxRecipientsPerMessage);
    }

    /**
     * Returns the largest message the provider accepts, in bytes, if it documents a limit.
     *
     * @return the limit
     * @since 0.1.0
     */
    public OptionalLong maxMessageSize() {
        return maxMessageSize == null ? OptionalLong.empty() : OptionalLong.of(maxMessageSize);
    }

    /**
     * Two sets of capabilities are equal when they support, understand and limit the same things.
     *
     * @param other the object to compare with
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransportCapabilities)) {
            return false;
        }
        TransportCapabilities that = (TransportCapabilities) other;
        return supported.equals(that.supported) && understood.equals(that.understood)
                && Objects.equals(maxRecipientsPerMessage, that.maxRecipientsPerMessage)
                && Objects.equals(maxMessageSize, that.maxMessageSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(supported, understood, maxRecipientsPerMessage, maxMessageSize);
    }

    /**
     * Describes the capabilities, such as
     * {@code TransportCapabilities[supports=[html, text], understands=[ResendOptions], maxRecipientsPerMessage=50]}.
     *
     * @return a readable description
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("TransportCapabilities[supports=").append(supported).append(", understands=[");
        String separator = "";
        for (Class<? extends EmailExtension> type : understood) {
            s.append(separator).append(type.getSimpleName());
            separator = ", ";
        }
        s.append(']');
        if (maxRecipientsPerMessage != null) {
            s.append(", maxRecipientsPerMessage=").append(maxRecipientsPerMessage);
        }
        if (maxMessageSize != null) {
            s.append(", maxMessageSize=").append(maxMessageSize);
        }
        return s.append(']').toString();
    }

    /**
     * Collects a transport's capabilities. Each call adds to what is already declared.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Set<Capability> supported = new LinkedHashSet<Capability>();
        private final Set<Class<? extends EmailExtension>> understood =
                new LinkedHashSet<Class<? extends EmailExtension>>();
        private Integer maxRecipientsPerMessage;
        private Long maxMessageSize;

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
            for (Capability capability : Objects.requireNonNull(capabilities, "capabilities")) {
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
            for (Class<? extends EmailExtension> type : Objects.requireNonNull(extensionTypes, "extensionTypes")) {
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
            this.maxRecipientsPerMessage = max;
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
            this.maxMessageSize = bytes;
            return this;
        }

        /**
         * Builds the capabilities.
         *
         * @return the capabilities; later changes to this builder don't affect them
         * @since 0.1.0
         */
        public TransportCapabilities build() {
            return new TransportCapabilities(this);
        }
    }
}
