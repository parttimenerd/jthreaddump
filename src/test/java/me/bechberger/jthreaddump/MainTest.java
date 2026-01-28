package me.bechberger.jthreaddump;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test for the CLI
 */
public class MainTest {
    @Test
    public void testStdin() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ByteArrayOutputStream berr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bout));
        System.setErr(new PrintStream(berr));
        try {
            String input = """
                    "t" #1 prio=5 tid=0x1 nid=0x2 runnable
                       java.lang.Thread.State: RUNNABLE
                       at java.lang.Thread.run(Thread.java:1)

                    """;
            System.setIn(new java.io.ByteArrayInputStream(input.getBytes()));
            Main.main(new String[] {});
            String output = bout.toString();
            assertEquals("""
                    source=unknown
                    threads[1]
                    "t" id=1 nid=2 prio=5 daemon=false state=RUNNABLE
                      at java.lang.Thread.run(Thread.java:1)
                    """.trim(), output.trim());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}