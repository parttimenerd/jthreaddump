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
 * Tests for ThreadChurnAnalyzer
 */
class ThreadChurnAnalyzerTest {

    private ThreadChurnAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new ThreadChurnAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("ThreadChurnAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertTrue(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
    }

    @Test
    void shouldNotAnalyzeSingleDump() {
        ThreadDump dump = createDump(createThread("main", 0x1L));
        AnalysisContext context = AnalysisContext.of(dump, includeAll());

        assertFalse(analyzer.canAnalyze(context));
    }

    @Nested
    class MultiDumpTests {

        @Test
        void shouldDetectStableThreadCount() {
            ThreadInfo t1 = createThread("main", 0x1L);
            ThreadInfo t2 = createThread("worker", 0x2L);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1, t2);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t1, t2);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

            assertFalse(result.getChurnSummary().potentialLeak());
            assertFalse(result.getChurnSummary().highChurn());
            assertEquals(2, result.getChurnSummary().firstCount());
            assertEquals(2, result.getChurnSummary().lastCount());
        }

        @Test
        void shouldDetectThreadGrowth() {
            ThreadInfo t1 = createThread("main", 0x1L);
            ThreadInfo t2 = createThread("worker-1", 0x2L);
            ThreadInfo t3 = createThread("worker-2", 0x3L);
            ThreadInfo t4 = createThread("worker-3", 0x4L);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t1, t2);
            ThreadDump dump3 = createDumpWithTimestamp(Instant.now().plusSeconds(10), t1, t2, t3);
            ThreadDump dump4 = createDumpWithTimestamp(Instant.now().plusSeconds(15), t1, t2, t3, t4);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2, dump3, dump4), includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

            assertTrue(result.getChurnSummary().potentialLeak());
            assertEquals(3, result.getChurnSummary().netGrowth());
            assertEquals(1, result.getChurnSummary().firstCount());
            assertEquals(4, result.getChurnSummary().lastCount());
        }

        @Test
        void shouldDetectHighChurn() {
            ThreadInfo main = createThread("main", 0x1L);
            ThreadInfo worker1 = createThread("worker-1", 0x10L);
            ThreadInfo worker2 = createThread("worker-2", 0x20L);
            ThreadInfo worker3 = createThread("worker-3", 0x30L);
            ThreadInfo worker4 = createThread("worker-4", 0x40L);

            // First dump: main + worker1, worker2
            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), main, worker1, worker2);
            // Second dump: main + worker3, worker4 (worker1, worker2 gone, worker3, worker4 new)
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), main, worker3, worker4);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

            // Should detect churn - 2 created and 2 destroyed
            assertEquals(2, result.getChurnSummary().totalCreated());
            assertEquals(2, result.getChurnSummary().totalDestroyed());
        }

        @Test
        void shouldTrackChurnEvents() {
            ThreadInfo main = createThread("main", 0x1L);
            ThreadInfo worker = createThread("worker", 0x2L);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), main);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), main, worker);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

            assertEquals(1, result.getChurnEvents().size());
            ThreadChurnAnalyzer.ChurnEvent event = result.getChurnEvents().getFirst();
            assertEquals(1, event.createdCount());
            assertEquals(0, event.destroyedCount());
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldAnalyzeThreadPoolProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dumps.add(loadDump("thread-pool-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertEquals(5, result.getThreadCounts().size());
        }

        @Test
        void shouldAnalyzeGCProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dumps.add(loadDump("gc-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

            assertNotNull(result);
        }

        @Test
        void shouldAnalyzeDeadlockProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                dumps.add(loadDump("deadlock-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            ThreadChurnAnalyzer.ThreadChurnResult result = analyzer.analyze(context);

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

    private ThreadInfo createThread(String name, Long nid) {
        return new ThreadInfo(name, 1L, nid, 5, true, Thread.State.RUNNABLE,
                100L, 1000L, List.of(), List.of(), null, null);
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(Instant.now(), "JVM", List.of(threads), null, "jstack");
    }

    private ThreadDump createDumpWithTimestamp(Instant timestamp, ThreadInfo... threads) {
        return new ThreadDump(timestamp, "JVM", List.of(threads), null, "jstack");
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}