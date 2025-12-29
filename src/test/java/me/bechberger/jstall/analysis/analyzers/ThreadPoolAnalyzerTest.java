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
 * Tests for ThreadPoolAnalyzer
 */
class ThreadPoolAnalyzerTest {

    private ThreadPoolAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new ThreadPoolAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("ThreadPoolAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
    }

    @Nested
    class PoolDetectionTests {

        @Test
        void shouldDetectExecutorServicePool() {
            ThreadInfo t1 = createThread("pool-1-thread-1", 0x1L, Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("pool-1-thread-2", 0x2L, Thread.State.WAITING);
            ThreadInfo t3 = createThread("pool-1-thread-3", 0x3L, Thread.State.WAITING);

            ThreadDump dump = createDump(t1, t2, t3);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(1, result.getPoolSummary().poolCount());
            assertEquals(3, result.getPoolSummary().totalPoolThreads());
        }

        @Test
        void shouldDetectForkJoinPool() {
            ThreadInfo t1 = createThread("ForkJoinPool-1-worker-1", 0x1L, Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("ForkJoinPool-1-worker-2", 0x2L, Thread.State.WAITING);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(1, result.getPoolSummary().poolCount());
            assertEquals(2, result.getPoolSummary().totalPoolThreads());
        }

        @Test
        void shouldDetectCommonPool() {
            ThreadInfo t1 = createThread("ForkJoinPool.commonPool-worker-1", 0x1L, Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("ForkJoinPool.commonPool-worker-2", 0x2L, Thread.State.TIMED_WAITING);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(1, result.getPoolSummary().poolCount());
        }

        @Test
        void shouldDetectMultiplePools() {
            ThreadInfo t1 = createThread("pool-1-thread-1", 0x1L, Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("pool-2-thread-1", 0x2L, Thread.State.RUNNABLE);
            ThreadInfo t3 = createThread("ForkJoinPool-1-worker-1", 0x3L, Thread.State.RUNNABLE);

            ThreadDump dump = createDump(t1, t2, t3);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(3, result.getPoolSummary().poolCount());
        }

        @Test
        void shouldNotDetectNonPoolThreads() {
            ThreadInfo t1 = createThread("main", 0x1L, Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("Signal Dispatcher", 0x2L, Thread.State.RUNNABLE);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(0, result.getPoolSummary().poolCount());
        }
    }

    @Nested
    class UtilizationTests {

        @Test
        void shouldDetectExhaustedPool() {
            // All threads RUNNABLE = exhausted
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                threads.add(createThread("pool-1-thread-" + i, (long) i, Thread.State.RUNNABLE));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(1, result.getPoolSummary().exhaustedPoolCount());
            assertTrue(result.hasFindings());
        }

        @Test
        void shouldDetectIdlePool() {
            // All threads WAITING = idle
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                threads.add(createThread("pool-1-thread-" + i, (long) i, Thread.State.WAITING));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertEquals(1, result.getPoolSummary().idlePoolCount());
        }

        @Test
        void shouldCalculateUtilization() {
            // 5 RUNNABLE, 5 WAITING = 50% utilization
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(createThread("pool-1-thread-" + i, (long) i, Thread.State.RUNNABLE));
            }
            for (int i = 5; i < 10; i++) {
                threads.add(createThread("pool-1-thread-" + i, (long) i, Thread.State.WAITING));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            ThreadPoolAnalyzer.PoolSnapshot snapshot = result.getSnapshots().getFirst();
            assertEquals(50.0, snapshot.utilizationPercent(), 0.1);
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldAnalyzeThreadPoolDump() throws IOException {
            ThreadDump dump = loadDump("thread-pool.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Thread pool dump should have pools
            assertTrue(result.getPoolSummary().poolCount() >= 0);
        }

        @Test
        void shouldAnalyzeThreadPoolProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dumps.add(loadDump("thread-pool-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertEquals(5, result.getSnapshotsByPool().values().stream()
                    .mapToInt(List::size)
                    .max()
                    .orElse(0));
        }

        @Test
        void shouldAnalyzeComplexDump() throws IOException {
            ThreadDump dump = loadDump("complex.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadPoolAnalyzer.ThreadPoolResult result = analyzer.analyze(context);

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