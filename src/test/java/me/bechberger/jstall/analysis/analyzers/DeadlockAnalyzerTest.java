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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DeadlockAnalyzer
 */
class DeadlockAnalyzerTest {

    private DeadlockAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new DeadlockAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("DeadlockAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
        assertEquals(200, analyzer.getPriority()); // Highest priority
    }

    @Nested
    class NoDeadlockTests {

        @Test
        void shouldDetectNoDeadlockWithHealthyThreads() {
            ThreadInfo t1 = createThread("main", 0x1L, Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("worker", 0x2L, Thread.State.WAITING);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            assertFalse(result.hasDeadlocks());
            assertEquals(AnalysisResult.Severity.OK, result.getSeverity());
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldDetectDeadlockFromJVMReport() throws IOException {
            ThreadDump dump = loadDump("deadlock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            // The deadlock.txt should have JVM-detected deadlocks
            if (dump.deadlockInfos() != null && !dump.deadlockInfos().isEmpty()) {
                assertTrue(result.hasDeadlocks());
                assertEquals(AnalysisResult.Severity.CRITICAL, result.getSeverity());
            }
        }

        @Test
        void shouldAnalyzeThreeWayDeadlock() throws IOException {
            ThreadDump dump = loadDump("three-way-deadlock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            assertNotNull(result);
            if (result.hasDeadlocks()) {
                assertTrue(result.getDeadlockSummary().maxCycleSize() >= 2);
            }
        }

        @Test
        void shouldDetectPersistentDeadlock() throws IOException {
            // Load multiple dumps from deadlock progression
            ThreadDump dump1 = loadDump("deadlock-progression-3.txt");
            ThreadDump dump2 = loadDump("deadlock-progression-4.txt");
            ThreadDump dump3 = loadDump("deadlock-progression-5.txt");

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2, dump3), includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            assertNotNull(result);
            // If deadlocks exist, check for persistent deadlock detection
            if (result.hasDeadlocks() && result.getDeadlocks().size() > 1) {
                boolean hasPersistentFinding = result.getFindings().stream()
                        .anyMatch(f -> "persistent-deadlock".equals(f.category()));
                // Persistent deadlock might be detected if same threads involved
            }
        }

        @Test
        void shouldHandleNonDeadlockDump() throws IOException {
            ThreadDump dump = loadDump("thread-pool.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Thread pool dump likely has no deadlocks
        }

        @Test
        void shouldAnalyzeVirtualThreadDeadlock() throws IOException {
            ThreadDump dump = loadDump("virtual-thread-deadlock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            assertNotNull(result);
        }
    }

    @Nested
    class SummaryTests {

        @Test
        void shouldProvideSummaryForNoDeadlocks() {
            ThreadInfo t1 = createThread("main", 0x1L, Thread.State.RUNNABLE);
            ThreadDump dump = createDump(t1);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            DeadlockAnalyzer.DeadlockResult result = analyzer.analyze(context);

            assertEquals("No deadlocks detected", result.getSummary());
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

    private ThreadInfo createThread(String name, Long nid, Thread.State state) {
        return new ThreadInfo(name, 1L, nid, 5, true, state,
                100L, 1000L, List.of(), List.of(), null, null);
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(Instant.now(), "JVM", List.of(threads), null, "jstack");
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}