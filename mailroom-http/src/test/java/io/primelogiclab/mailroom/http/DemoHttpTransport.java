package io.primelogiclab.mailroom.http;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.net.URI;
import java.time.Duration;

/**
 * The smallest real HTTP transport: JSON out through the engine, {@link FailureMapper} on errors. It shows the TCK's
 * standard HTTP cases and this module's defaults agree, and that nothing here leaks the API key.
 */
final class DemoHttpTransport implements MailTransport {

    private static final FailureMapper FAILURES = FailureMapper.builder("demo").build();

    private final String name;
    private final URI endpoint;
    private final Secret apiKey;
    private final Duration readTimeout;
    private final HttpEngine engine = HttpEngines.urlConnection();

    DemoHttpTransport(String name, URI endpoint, Secret apiKey, Duration readTimeout) {
        this.name = name;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.readTimeout = readTimeout;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerId() {
        return "demo";
    }

    @Override
    public TransportCapabilities capabilities() {
        return TransportCapabilities.builder().supports(Capability.TEXT).build();
    }

    @Override
    public SendResult send(Email email, SendContext context) {
        byte[] body = JsonWriter.object()
                .field("from", email.from().get().format())
                .strings("to", formatted(email))
                .field("subject", email.subject())
                .field("text", email.text())
                .toBytes();
        HttpRequest request = HttpRequest.post(endpoint).header("X-Api-Key", apiKey.reveal()).json(body)
                .readTimeout(readTimeout).build();
        try {
            HttpResponse response = engine.execute(request);
            if (!response.isSuccessful()) {
                throw FAILURES.failure(response, context.clock());
            }
            return SendResult.builder().transportName(name).providerId(providerId())
                    .acceptedAt(context.clock().instant()).build();
        } catch (SendFailedException e) {
            throw e.toBuilder().transportName(name).providerId(providerId()).build();
        }
    }

    @Override
    public String toString() {
        return "DemoHttpTransport[" + name + "]";
    }

    private static java.util.List<String> formatted(Email email) {
        java.util.List<String> to = new java.util.ArrayList<String>();
        for (io.primelogiclab.mailroom.Address address : email.recipients()) {
            to.add(address.format());
        }
        return to;
    }
}
