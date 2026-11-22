package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.spi.BatchItemResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What design 0.5 says the Java 21 set adds on top of the shared API: records and a sealed exception hierarchy. */
class Java21SetTest {

    @Test
    void valueTypesAreRecords() {
        for (Class<?> type : List.of(Attempt.class, SendResult.class, BatchItemResult.class, Precheck.class,
                Capability.class, TransportCapabilities.class, SendContext.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
        for (Class<?> type : List.of(Address.class, Attachment.class, Email.class, Secret.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName() + " stays a class").isFalse();
        }
    }

    @Test
    void theExceptionHierarchyIsSealedButItsSubclassesAreOpen() {
        assertThat(MailroomException.class.isSealed()).isTrue();
        assertThat(MailroomException.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
                InvalidConfigException.class, InvalidEmailException.class, NoEligibleTransportException.class,
                SendFailedException.class);
        for (Class<?> type : MailroomException.class.getPermittedSubclasses()) {
            assertThat(type.isSealed()).as(type.getSimpleName()).isFalse();
            assertThat(Modifier.isFinal(type.getModifiers())).as(type.getSimpleName()).isFalse();
        }
    }

    @Test
    void recordsStillValidateAndMask() {
        var precheck = new Precheck(java.util.Optional.of("  rejected for someone@example.com  "));
        assertThat(precheck.reason()).contains("rejected for s***@example.com");
        assertThat(precheck).isEqualTo(Precheck.reject("rejected for someone@example.com"));
        assertThat(Capability.of("html")).isEqualTo(Capability.HTML).isEqualTo(new Capability("html"));
    }
}
