package me.bechberger.jthreaddump.parser;

import me.bechberger.jthreaddump.model.DeadlockInfo;
import me.bechberger.jthreaddump.model.ThreadDump;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parsing deadlock information from thread dumps
 */
class DeadlockParsingTest {

    @Test
    void testParseDeadlockSection() throws IOException {
        String dumpWithDeadlock = """
                Full thread dump Java HotSpot(TM) 64-Bit Server VM:
                
                "DeadlockThread-A" #10 prio=5 tid=0x1000 nid=0x2000 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED
                   at test.Example.method(Example.java:10)
                   - waiting to lock <0x000000052c830078> (a java.lang.Object)
                   - locked <0x000000052c830070> (a java.lang.Object)
                
                "DeadlockThread-B" #11 prio=5 tid=0x2000 nid=0x3000 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED
                   at test.Example.method(Example.java:20)
                   - waiting to lock <0x000000052c830070> (a java.lang.Object)
                   - locked <0x000000052c830078> (a java.lang.Object)
                
                Found one Java-level deadlock:
                =============================
                "DeadlockThread-A":
                  waiting to lock monitor 0x0000600002f9dba0 (object 0x000000052c830070, a java.lang.Object),
                  which is held by "DeadlockThread-B"
                
                "DeadlockThread-B":
                  waiting to lock monitor 0x0000600002fbfe90 (object 0x000000052c830078, a java.lang.Object),
                  which is held by "DeadlockThread-A"
                
                Java stack information for the threads listed above:
                ===================================================
                "DeadlockThread-A":
                \tat test.Example.lambda$deadlockScenario$1(Example.java:98)
                \t- waiting to lock <0x000000052c830070> (a java.lang.Object)
                \t- locked <0x000000052c830078> (a java.lang.Object)
                \tat test.Example.run(Example.java:100)
                "DeadlockThread-B":
                \tat test.Example.lambda$deadlockScenario$2(Example.java:114)
                \t- waiting to lock <0x000000052c830078> (a java.lang.Object)
                \t- locked <0x000000052c830070> (a java.lang.Object)
                \tat test.Example.run(Example.java:120)
                
                Found 1 deadlock.
                """;

        ThreadDump dump = ThreadDumpParser.parse(dumpWithDeadlock);

        // Verify basic dump structure
        assertNotNull(dump);
        assertEquals(2, dump.threads().size());

        // Verify deadlock information was parsed
        assertNotNull(dump.deadlockInfos(), "Deadlock infos should be present");
        assertFalse(dump.deadlockInfos().isEmpty(), "Should have at least one deadlock");

        DeadlockInfo deadlockInfo = dump.deadlockInfos().get(0);

        // Verify deadlocked threads
        assertNotNull(deadlockInfo.threads());
        assertEquals(2, deadlockInfo.threads().size(), "Should have 2 deadlocked threads");

        // Verify first deadlocked thread
        DeadlockInfo.DeadlockedThread threadA = deadlockInfo.threads().stream()
                .filter(t -> t.threadName().equals("DeadlockThread-A"))
                .findFirst()
                .orElse(null);
        assertNotNull(threadA, "DeadlockThread-A should be in deadlock info");
        assertEquals("0x0000600002f9dba0", threadA.waitingForMonitor());
        assertEquals("0x000000052c830070", threadA.waitingForObject());
        assertEquals("java.lang.Object", threadA.waitingForObjectType());
        assertEquals("DeadlockThread-B", threadA.heldBy());

        // Verify second deadlocked thread
        DeadlockInfo.DeadlockedThread threadB = deadlockInfo.threads().stream()
                .filter(t -> t.threadName().equals("DeadlockThread-B"))
                .findFirst()
                .orElse(null);
        assertNotNull(threadB, "DeadlockThread-B should be in deadlock info");
        assertEquals("0x0000600002fbfe90", threadB.waitingForMonitor());
        assertEquals("0x000000052c830078", threadB.waitingForObject());
        assertEquals("java.lang.Object", threadB.waitingForObjectType());
        assertEquals("DeadlockThread-A", threadB.heldBy());

        // Verify stack traces
        assertNotNull(threadA.stackTrace());
        assertTrue(threadA.stackTrace().size() > 0, "Should have stack frames");
        assertNotNull(threadB.stackTrace());
        assertTrue(threadB.stackTrace().size() > 0, "Should have stack frames");

        // Verify locks
        assertNotNull(threadA.locks());
        assertTrue(threadA.locks().size() > 0, "Should have lock information");
        assertNotNull(threadB.locks());
        assertTrue(threadB.locks().size() > 0, "Should have lock information");
    }

    @Test
    void testParseNonDeadlockDump() throws IOException {
        String normalDump = """
                Full thread dump Java HotSpot(TM) 64-Bit Server VM:
                
                "main" #1 prio=5 tid=0x1000 nid=0x2000 runnable
                   java.lang.Thread.State: RUNNABLE
                   at test.Example.main(Example.java:10)
                """;

        ThreadDump dump = ThreadDumpParser.parse(normalDump);

        assertNotNull(dump);
        assertEquals(1, dump.threads().size());
        assertTrue(dump.deadlockInfos().isEmpty(), "No deadlock info should be present");
    }

    @Test
    void testParseThreeWayDeadlock() throws IOException {
        String threeWayDeadlock = """
                Full thread dump:
                
                Found one Java-level deadlock:
                =============================
                "Thread-A":
                  waiting to lock monitor 0x1111 (object 0xaaaa, a java.lang.Object),
                  which is held by "Thread-B"
                
                "Thread-B":
                  waiting to lock monitor 0x2222 (object 0xbbbb, a java.lang.Object),
                  which is held by "Thread-C"
                
                "Thread-C":
                  waiting to lock monitor 0x3333 (object 0xcccc, a java.lang.Object),
                  which is held by "Thread-A"
                
                Found 1 deadlock.
                """;

        ThreadDump dump = ThreadDumpParser.parse(threeWayDeadlock);

        assertNotNull(dump);
        assertFalse(dump.deadlockInfos().isEmpty());
        DeadlockInfo deadlockInfo = dump.deadlockInfos().get(0);
        assertEquals(3, deadlockInfo.threads().size(),
                "Should detect all 3 threads in deadlock cycle");

        // Verify circular dependency
        DeadlockInfo.DeadlockedThread threadA = findDeadlockedThread(deadlockInfo, "Thread-A");
        DeadlockInfo.DeadlockedThread threadB = findDeadlockedThread(deadlockInfo, "Thread-B");
        DeadlockInfo.DeadlockedThread threadC = findDeadlockedThread(deadlockInfo, "Thread-C");

        assertNotNull(threadA);
        assertNotNull(threadB);
        assertNotNull(threadC);

        assertEquals("Thread-B", threadA.heldBy());
        assertEquals("Thread-C", threadB.heldBy());
        assertEquals("Thread-A", threadC.heldBy());
    }

    private DeadlockInfo.DeadlockedThread findDeadlockedThread(DeadlockInfo info, String threadName) {
        return info.threads().stream()
                .filter(t -> t.threadName().equals(threadName))
                .findFirst()
                .orElse(null);
    }
}