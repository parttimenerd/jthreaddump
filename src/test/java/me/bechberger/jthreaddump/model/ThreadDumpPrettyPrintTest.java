package me.bechberger.jthreaddump.model;

import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ThreadDumpPrettyPrintTest {

    @Test
    void prettyPrintIsCompactButContainsAllKeyInfo() throws IOException {
        String dumpText = """
                "t" #1 prio=5 tid=0x1 nid=0x2 runnable
                   java.lang.Thread.State: RUNNABLE
                   at java.lang.Thread.run(Thread.java:1)
                
                """;

        ThreadDump dump = ThreadDumpParser.parse(dumpText);
        String pp = dump.prettyPrint();
        assertEquals("""
                source=unknown
                threads[1]
                "t" id=1 nid=2 prio=5 daemon=false state=RUNNABLE
                  at java.lang.Thread.run(Thread.java:1)
                """, pp);
    }

    @Test
    void prettyPrintIncludesDeadlockInfoWhenPresent() {
        ThreadDump dump = new ThreadDump(
                null,
                "JVM",
                java.util.List.of(),
                null,
                "jstack",
                java.util.List.of(
                        new DeadlockInfo(java.util.List.of(
                                new DeadlockInfo.DeadlockedThread(
                                        "A", "0x1", "0x2", "java.lang.Object", "B",
                                        java.util.List.of(new StackFrame("C", "m", "C.java", 1)),
                                        java.util.List.of(new LockInfo("0x2", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK))
                                ),
                                new DeadlockInfo.DeadlockedThread(
                                        "B", "0x3", "0x4", "java.lang.Object", "A",
                                        java.util.List.of(),
                                        java.util.List.of()
                                )
                        ))
                )
        );

        String pp = dump.prettyPrint();
        assertEquals("""
                source=jstack
                jvm=JVM
                threads[0]
                deadlocks[1]
                cycle[2]: A waits(java.lang.Object) heldBy=B -> B waits(java.lang.Object) heldBy=A -> A
                  A waitingForMonitor=0x1 waitingForObject=0x2 type=java.lang.Object heldBy=B locks=waiting to lock <0x2> (java.lang.Object)
                    at C.m(C.java:1)
                  B waitingForMonitor=0x3 waitingForObject=0x4 type=java.lang.Object heldBy=A
                """, pp);
    }
}