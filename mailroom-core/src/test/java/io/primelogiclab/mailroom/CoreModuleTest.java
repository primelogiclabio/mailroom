package io.primelogiclab.mailroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** The module compiles to Java 21 bytecode, so one jar runs on Java 8, 21 and 25. */
class CoreModuleTest {

    @Test
    void compiledForJava21() throws Exception {
        try (InputStream raw = Email.class.getResourceAsStream("Email.class")) {
            assertThat(raw).isNotNull();
            DataInputStream in = new DataInputStream(raw);
            assertThat(in.readInt()).as("class file magic").isEqualTo(0xCAFEBABE);
            in.readUnsignedShort();
            assertThat(in.readUnsignedShort()).as("class file major version").isEqualTo(65);
        }
    }
}
