package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.*;
import me.bechberger.jstall.parser.ThreadDumpParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LockContentionAnalyzer
 */
class LockContentionAnalyzerTest {

    private LockContentionAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new LockContentionAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("LockContentionAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
    }

    @Nested
    class SingleDumpTests {

        @Test
        void shouldDetectNoContentionWithoutWaiters() {
            ThreadInfo owner = createThreadWithLock("owner", 0x1L, "0xabc", "locked");
            ThreadDump dump = createDump(owner);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            assertTrue(result.getContentions().isEmpty());
            assertEquals(0, result.getLockSummary().totalContentedLocks());
        }

        @Test
        void shouldDetectContentionWithWaiters() {
            ThreadInfo owner = createThreadWithLock("owner", 0x1L, "0xabc", "locked");
            ThreadInfo waiter1 = createWaitingThread("waiter-1", 0x2L, "0xabc");
            ThreadInfo waiter2 = createWaitingThread("waiter-2", 0x3L, "0xabc");

            ThreadDump dump = createDump(owner, waiter1, waiter2);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            assertFalse(result.getContentions().isEmpty());
            assertEquals(1, result.getLockSummary().totalContentedLocks());
            assertTrue(result.getLockSummary().maxWaiters() >= 2);
        }

        @Test
        void shouldIdentifyHotLock() {
            ThreadInfo owner = createThreadWithLock("owner", 0x1L, "0xabc", "locked");
            List<ThreadInfo> threads = new ArrayList<>();
            threads.add(owner);

            for (int i = 0; i < 10; i++) {
                threads.add(createWaitingThread("waiter-" + i, (long) (i + 2), "0xabc"));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            assertEquals(1, result.getLockSummary().hotLocks());
            assertTrue(result.hasFindings());
            assertEquals(AnalysisResult.Severity.ERROR, result.getSeverity());
        }
    }

    @Nested
    class MultiDumpTests {

        @Test
        void shouldDetectLongHeldLock() {
            ThreadInfo owner = createThreadWithLock("owner", 0x1L, "0xabc", "locked");
            ThreadInfo waiter = createWaitingThread("waiter", 0x2L, "0xabc");

            ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM",
                    List.of(owner, waiter), null, "jstack");
            ThreadDump dump2 = new ThreadDump(Instant.now().plusSeconds(5), "JVM",
                    List.of(owner, waiter), null, "jstack");
            ThreadDump dump3 = new ThreadDump(Instant.now().plusSeconds(10), "JVM",
                    List.of(owner, waiter), null, "jstack");

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2, dump3), includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            // Should detect long-held lock
            assertTrue(result.hasFindings());
            boolean hasLongHeldFinding = result.getFindings().stream()
                    .anyMatch(f -> "long-held-lock".equals(f.category()));
            assertTrue(hasLongHeldFinding);
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldAnalyzeDeadlockDump() throws IOException {
            ThreadDump dump = loadDump("deadlock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Deadlock dumps may have lock contention
        }

        @Test
        void shouldAnalyzeThreadPoolDump() throws IOException {
            ThreadDump dump = loadDump("thread-pool.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            assertNotNull(result);
        }

        @Test
        void shouldAnalyzeReadWriteLockDump() throws IOException {
            ThreadDump dump = loadDump("read-write-lock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            LockContentionAnalyzer.LockContentionResult result = analyzer.analyze(context);

            assertNotNull(result);
        }
    }

    // Helper methods

    private ThreadDump loadDump(String resourceName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(is, "Resource not found: " + resourceName);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return parser.parse(content);
        }
    }

    private ThreadInfo createThreadWithLock(String name, Long nid, String lockId, String lockType) {
        LockInfo lock = new LockInfo(lockId, "java.lang.Object", lockType);
        return new ThreadInfo(name, 1L, nid, 5, true, Thread.State.RUNNABLE,
                100L, 1000L, List.of(), List.of(lock), null, null);
    }

    private ThreadInfo createWaitingThread(String name, Long nid, String waitingForLock) {
        LockInfo lock = new LockInfo(waitingForLock, "java.lang.Object", "waiting to lock");
        return new ThreadInfo(name, 1L, nid, 5, true, Thread.State.BLOCKED,
                10L, 100L, List.of(), List.of(lock), waitingForLock, null);
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(Instant.now(), "JVM", List.of(threads), null, "jstack");
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}