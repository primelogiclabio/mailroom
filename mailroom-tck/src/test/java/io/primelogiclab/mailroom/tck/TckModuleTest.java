package io.primelogiclab.mailroom.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Smoke test: the module builds, compiles to Java 8 bytecode, and its tests run. */
class TckModuleTest {

    @Test
    void compiledForJava21() throws Exception {
        String resource = "/" + getClass().getPackage().getName().replace('.', '/') + "/package-info.class";
        try (InputStream raw = getClass().getResourceAsStream(resource)) {
            assertThat(raw).as(resource).isNotNull();
            DataInputStream in = new DataInputStream(raw);
            assertThat(in.readInt()).as("class file magic").isEqualTo(0xCAFEBABE);
            in.readUnsignedShort();
            assertThat(in.readUnsignedShort()).as("class file major version").isEqualTo(65);
        }
    }
}
