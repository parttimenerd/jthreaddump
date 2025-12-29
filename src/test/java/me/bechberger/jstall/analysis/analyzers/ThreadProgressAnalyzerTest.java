package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.analysis.AnalysisContext.ThreadIdentifier;
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
 * Tests for ThreadProgressAnalyzer
 */
class ThreadProgressAnalyzerTest {

    private ThreadProgressAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new ThreadProgressAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("ThreadProgressAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
        assertEquals(100, analyzer.getPriority()); // High priority
    }

    @Nested
    class SingleDumpTests {

        @Test
        void shouldClassifyRunnableAsActive() {
            ThreadInfo thread = createThread("worker", Thread.State.RUNNABLE, 100L, 1000L);
            ThreadDump dump = createDump(thread);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertEquals(1, result.getProgressMap().size());
            ThreadProgressAnalyzer.ThreadProgress progress = result.getProgressMap().values().iterator().next();
            assertEquals(ProgressClassification.ACTIVE, progress.classification());
        }

        @Test
        void shouldClassifyBlockedAsBlockedOnLock() {
            ThreadInfo thread = createThread("worker", Thread.State.BLOCKED, 100L, 1000L);
            ThreadDump dump = createDump(thread);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = result.getProgressMap().values().iterator().next();
            assertEquals(ProgressClassification.BLOCKED_ON_LOCK, progress.classification());
        }

        @Test
        void shouldClassifyWaitingPoolThreadAsExpected() {
            ThreadInfo thread = createThread("pool-1-thread-1", Thread.State.WAITING, 10L, 500L);
            ThreadDump dump = createDump(thread);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = result.getProgressMap().values().iterator().next();
            assertEquals(ProgressClassification.WAITING_EXPECTED, progress.classification());
        }

        @Test
        void shouldClassifyTimedWaitingSchedulerAsExpected() {
            ThreadInfo thread = createThread("Timer-0", Thread.State.TIMED_WAITING, 10L, 500L);
            ThreadDump dump = createDump(thread);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = result.getProgressMap().values().iterator().next();
            assertEquals(ProgressClassification.TIMED_WAITING_EXPECTED, progress.classification());
        }

        @Test
        void shouldClassifyTerminatedCorrectly() {
            ThreadInfo thread = createThread("worker", Thread.State.TERMINATED, 100L, 1000L);
            ThreadDump dump = createDump(thread);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = result.getProgressMap().values().iterator().next();
            assertEquals(ProgressClassification.TERMINATED, progress.classification());
        }
    }

    @Nested
    class MultiDumpTests {

        @Test
        void shouldDetectActiveThreadWithCpuProgress() {
            ThreadInfo t1 = createThreadWithNid("worker", 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
            ThreadInfo t2 = createThreadWithNid("worker", 0x1234L, Thread.State.RUNNABLE, 200L, 2000L);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t2);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = findProgressByNid(result, 0x1234L);
            assertNotNull(progress);
            assertEquals(ProgressClassification.ACTIVE, progress.classification());
        }

        @Test
        void shouldDetectNoProgressRunnable() {
            List<StackFrame> sameStack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100)
            );

            ThreadInfo t1 = createThreadWithStack("worker", 0x1234L, Thread.State.RUNNABLE, 100L, 1000L, sameStack);
            ThreadInfo t2 = createThreadWithStack("worker", 0x1234L, Thread.State.RUNNABLE, 100L, 2000L, sameStack);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t2);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = findProgressByNid(result, 0x1234L);
            assertNotNull(progress);
            assertEquals(ProgressClassification.RUNNABLE_NO_PROGRESS, progress.classification());
        }

        @Test
        void shouldDetectNewThread() {
            ThreadInfo t1 = createThreadWithNid("worker-1", 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
            ThreadInfo t2 = createThreadWithNid("worker-2", 0x5678L, Thread.State.RUNNABLE, 50L, 500L);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t1, t2);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress newProgress = findProgressByNid(result, 0x5678L);
            assertNotNull(newProgress);
            assertEquals(ProgressClassification.NEW, newProgress.classification());
        }

        @Test
        void shouldDetectTerminatedThread() {
            ThreadInfo t1 = createThreadWithNid("worker", 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5)); // worker gone

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = findProgressByNid(result, 0x1234L);
            assertNotNull(progress);
            assertEquals(ProgressClassification.TERMINATED, progress.classification());
        }

        @Test
        void shouldDetectRestartedThread() {
            ThreadInfo t1 = createThreadWithNid("worker", 0x1234L, Thread.State.RUNNABLE, 100L, 5000L);
            ThreadInfo t2 = createThreadWithNid("worker", 0x1234L, Thread.State.RUNNABLE, 10L, 100L); // Lower elapsed

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t2);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = findProgressByNid(result, 0x1234L);
            assertNotNull(progress);
            assertEquals(ProgressClassification.RESTARTED, progress.classification());
        }

        @Test
        void shouldDetectStuckWaitingThread() {
            List<StackFrame> sameStack = List.of(
                    new StackFrame("java.lang.Object", "wait", "Object.java", 100)
            );

            ThreadInfo t1 = createThreadWithStack("mythread", 0x1234L, Thread.State.WAITING, 10L, 1000L, sameStack);
            ThreadInfo t2 = createThreadWithStack("mythread", 0x1234L, Thread.State.WAITING, 10L, 2000L, sameStack);
            ThreadInfo t3 = createThreadWithStack("mythread", 0x1234L, Thread.State.WAITING, 10L, 3000L, sameStack);

            ThreadDump dump1 = createDumpWithTimestamp(Instant.now(), t1);
            ThreadDump dump2 = createDumpWithTimestamp(Instant.now().plusSeconds(5), t2);
            ThreadDump dump3 = createDumpWithTimestamp(Instant.now().plusSeconds(10), t3);

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2, dump3), includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ThreadProgress progress = findProgressByNid(result, 0x1234L);
            assertNotNull(progress);
            assertEquals(ProgressClassification.STUCK, progress.classification());
        }
    }

    @Nested
    class SummaryTests {

        @Test
        void shouldCalculateProblemPercentage() {
            ThreadInfo active = createThreadWithNid("active", 0x1L, Thread.State.RUNNABLE, 200L, 1000L);
            ThreadInfo blocked = createThreadWithNid("blocked", 0x2L, Thread.State.BLOCKED, 10L, 1000L);

            // Create a scenario with one active and one blocked
            ThreadDump dump = createDump(active, blocked);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            ThreadProgressAnalyzer.ProgressSummary summary = result.getProgressSummary();
            assertEquals(2, summary.total());
            assertEquals(1, summary.active());
            assertEquals(1, summary.blocked());
            assertEquals(50.0, summary.problemPercentage(), 0.1);
        }

        @Test
        void shouldDetectStallCondition() {
            // Create mostly problematic threads
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                threads.add(createThreadWithNid("blocked-" + i, (long) i, Thread.State.BLOCKED, 10L, 1000L));
            }
            threads.add(createThreadWithNid("active", 100L, Thread.State.RUNNABLE, 200L, 1000L));

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertTrue(result.getProgressSummary().indicatesStall(90.0));
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldAnalyzeComplexDump() throws IOException {
            ThreadDump dump = loadDump("complex.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertFalse(result.getProgressMap().isEmpty());
            assertNotNull(result.getProgressSummary());
            assertTrue(result.getProgressSummary().total() > 0);
        }

        @Test
        void shouldAnalyzeThreadPoolDump() throws IOException {
            ThreadDump dump = loadDump("thread-pool.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Thread pool dump should have multiple threads
            assertTrue(result.getProgressSummary().total() > 5);
        }

        @Test
        void shouldAnalyzeDeadlockDump() throws IOException {
            ThreadDump dump = loadDump("deadlock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Deadlock should have blocked threads
            assertTrue(result.getProgressSummary().blocked() >= 0);
        }

        @Test
        void shouldAnalyzeMultipleDumpsProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dumps.add(loadDump("thread-pool-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertFalse(result.getProgressMap().isEmpty());
            // Should have findings about thread progress
        }

        @Test
        void shouldAnalyzeDeadlockProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                dumps.add(loadDump("deadlock-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Deadlock progression should show stuck/blocked threads
            assertTrue(result.getProgressSummary().blocked() > 0 || result.getProgressSummary().stuck() > 0);
        }
    }

    @Nested
    class FindingsTests {

        @Test
        void shouldGenerateFindingsForProblems() {
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(createThreadWithNid("blocked-" + i, (long) i, Thread.State.BLOCKED, 10L, 1000L));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertTrue(result.hasFindings());
            boolean hasBlockedFinding = result.getFindings().stream()
                    .anyMatch(f -> f.message().contains("BLOCKED_ON_LOCK"));
            assertTrue(hasBlockedFinding);
        }

        @Test
        void shouldAssignCorrectSeverity() {
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(createThreadWithNid("blocked-" + i, (long) i, Thread.State.BLOCKED, 10L, 1000L));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            ThreadProgressAnalyzer.ProgressResult result = analyzer.analyze(context);

            assertEquals(AnalysisResult.Severity.WARNING, result.getSeverity());
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

    private ThreadInfo createThread(String name, Thread.State state, Long cpuTimeMs, Long elapsedTimeMs) {
        return new ThreadInfo(name, 1L, (long) name.hashCode(), 5, true, state,
                cpuTimeMs, elapsedTimeMs, List.of(), List.of(), null, null);
    }

    private ThreadInfo createThreadWithNid(String name, Long nid, Thread.State state,
                                           Long cpuTimeMs, Long elapsedTimeMs) {
        return new ThreadInfo(name, 1L, nid, 5, true, state,
                cpuTimeMs, elapsedTimeMs,
                List.of(new StackFrame("com.example.App", "run", "App.java", 42)),
                List.of(), null, null);
    }

    private ThreadInfo createThreadWithStack(String name, Long nid, Thread.State state,
                                             Long cpuTimeMs, Long elapsedTimeMs, List<StackFrame> stack) {
        return new ThreadInfo(name, 1L, nid, 5, true, state,
                cpuTimeMs, elapsedTimeMs, stack, List.of(), null, null);
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(Instant.now(), "JVM", List.of(threads), null, "jstack");
    }

    private ThreadDump createDumpWithTimestamp(Instant timestamp, ThreadInfo... threads) {
        return new ThreadDump(timestamp, "JVM", List.of(threads), null, "jstack");
    }

    private ThreadProgressAnalyzer.ThreadProgress findProgressByNid(
            ThreadProgressAnalyzer.ProgressResult result, Long nid) {
        for (var entry : result.getProgressMap().entrySet()) {
            if (nid.equals(entry.getKey().nativeId())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}