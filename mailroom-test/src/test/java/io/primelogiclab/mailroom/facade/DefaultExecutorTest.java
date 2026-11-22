package io.primelogiclab.mailroom.facade;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** In the Java 21 set the default executor is always one virtual thread per send, without reflection. */
class DefaultExecutorTest {

    /** Records the thread each send runs on. */
    static final class ThreadRecorder implements MailTransport {
        final AtomicReference<Thread> thread = new AtomicReference<>();
        private final InMemoryTransport delegate = InMemoryTransport.named("primary");

        @Override
        public String name() {
            return "primary";
        }

        @Override
        public String providerId() {
            return "recorder";
        }

        @Override
        public TransportCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public SendResult send(Email email, SendContext context) {
            thread.set(Thread.currentThread());
            return delegate.send(email, context);
        }
    }

    @Test
    void sendAsyncRunsOnAVirtualThread() throws Exception {
        var recorder = new ThreadRecorder();
        try (var mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(recorder).build()) {
            var result = mailroom.sendAsync(Email.builder().to("player@example.com").subject("Hi").text("Hello").build())
                    .get(10, TimeUnit.SECONDS);
            assertThat(result.transportName()).isEqualTo("primary");
        }
        var ran = recorder.thread.get();
        assertThat(ran).isNotSameAs(Thread.currentThread());
        assertThat(ran.isVirtual()).as("the default executor runs each send on a virtual thread").isTrue();
    }
}
