package io.primelogiclab.mailroom.internal;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;

/**
 * Gives a transport the name it was registered under in a {@code Mailroom}, including in its results and failures.
 * Not API: this package may change in any release.
 */
public final class NamedTransport implements MailTransport {

    private final String name;
    private final MailTransport delegate;

    /**
     * Wraps a transport under another name.
     *
     * @param name     the configured name
     * @param delegate the transport
     */
    public NamedTransport(String name, MailTransport delegate) {
        this.name = name;
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public TransportCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public Precheck precheck(Email email) {
        return delegate.precheck(email);
    }

    @Override
    public SendResult send(Email email, SendContext context) {
        try {
            return delegate.send(email, context).toBuilder().transportName(name).build();
        } catch (SendFailedException e) {
            throw e.toBuilder().transportName(name).build();
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public String toString() {
        return name + " (" + delegate + ")";
    }
}
