package io.primelogiclab.mailroom.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import org.junit.jupiter.api.Test;

/** Smoke test: the test kit is compiled for its Java set. */
class TestKitModuleTest {

    @Test
    void compiledForJava8() throws Exception {
        try (DataInputStream in = new DataInputStream(
                InMemoryTransport.class.getResourceAsStream("InMemoryTransport.class"))) {
            in.readInt();
            in.readUnsignedShort();
            assertThat(in.readUnsignedShort()).as("class file major version").isEqualTo(52);
        }
    }
}
