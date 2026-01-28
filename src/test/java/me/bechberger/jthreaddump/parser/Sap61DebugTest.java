package me.bechberger.jthreaddump.parser;

import me.bechberger.jthreaddump.model.ThreadDump;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Sap61DebugTest {

    private String loadResource(String fileName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IOException("Resource not found: " + fileName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void debugDaemonParsingSap61() throws IOException {
        String content = loadResource("sapjvm/sapjvm-threaddump-61.txt");
        ThreadDump td = ThreadDumpParser.parse(content);
        assertFalse(td.threads().isEmpty());
        var t0 = td.threads().getFirst();
        // Ensure we don't regress back to null
        assertNotNull(t0.daemon(), "daemon should be parsed (true/false), not null");
    }
}