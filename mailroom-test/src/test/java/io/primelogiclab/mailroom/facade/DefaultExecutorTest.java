package io.primelogiclab.mailroom.facade;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.internal.Jdk;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import io.primelogiclab.mailroom.test.InMemoryTransport;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The CORE-7 done-when: the same test passes on JDK 8 (a pool thread) and on 21 and 25 (a virtual thread). CI runs
 * it on all three with -Dtest.jdk.
 */
class DefaultExecutorTest {

    /** Records the thread each send runs on. */
    static final class ThreadRecorder implements MailTransport {
        final AtomicReference<Thread> thread = new AtomicReference<Thread>();
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

    private static int javaFeatureVersion() {
        String spec = System.getProperty("java.specification.version");
        return Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
    }

    @Test
    void sendAsyncRunsOnAVirtualThreadFromJava21AndOnAPoolThreadBefore() throws Exception {
        ThreadRecorder recorder = new ThreadRecorder();
        try (Mailroom mailroom = Mailroom.builder().defaultFrom("shop@example.com").transport(recorder).build()) {
            SendResult result = mailroom.sendAsync(
                    Email.builder().to("player@example.com").subject("Hi").text("Hello").build())
                    .get(10, TimeUnit.SECONDS);
            assertThat(result.transportName()).isEqualTo("primary");
        }
        Thread ran = recorder.thread.get();
        assertThat(ran).isNotSameAs(Thread.currentThread());
        if (javaFeatureVersion() >= 21) {
            assertThat(Jdk.isVirtual(ran)).as("Java " + javaFeatureVersion() + " should use a virtual thread")
                    .isTrue();
        } else {
            assertThat(Jdk.isVirtual(ran)).isFalse();
            assertThat(ran.getName()).startsWith("mailroom-send-");
            assertThat(ran.isDaemon()).isTrue();
        }
    }
}
