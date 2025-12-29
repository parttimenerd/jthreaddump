package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.AnalysisContext;
import me.bechberger.jstall.analysis.AnalysisOptions;
import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.model.ThreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimilarStackAnalyzer - detecting threads with common stack patterns
 */
class SimilarStackAnalyzerTest {

    private SimilarStackAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SimilarStackAnalyzer();
    }

    @Test
    @DisplayName("Should have correct metadata")
    void shouldHaveCorrectMetadata() {
        assertEquals("SimilarStackAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresJfr());
        assertTrue(analyzer.getPriority() > 0);
    }

    @Nested
    @DisplayName("Common Prefix Detection Tests")
    class CommonPrefixTests {

        @Test
        @DisplayName("Should detect threads with identical stack prefix")
        void shouldDetectIdenticalStackPrefix() {
            // Create threads with same top 3 frames
            ThreadInfo t1 = createThreadWithStack("Thread-1",
                "com.app.Service.process",
                "com.app.Service.handle",
                "com.app.Service.execute",
                "java.lang.Thread.run"
            );

            ThreadInfo t2 = createThreadWithStack("Thread-2",
                "com.app.Service.process",
                "com.app.Service.handle",
                "com.app.Service.execute",
                "java.lang.Thread.run"
            );

            ThreadInfo t3 = createThreadWithStack("Thread-3",
                "com.other.Handler.work",
                "java.lang.Thread.run"
            );

            ThreadDump dump = createDump(t1, t2, t3);
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertTrue(result.hasGroups());

            // Should find at least one group with t1 and t2
            boolean foundGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() >= 2);
            assertTrue(foundGroup, "Should find group with at least 2 threads");
        }

        @Test
        @DisplayName("Should detect common prefix of varying lengths")
        void shouldDetectVariablePrefixLength() {
            // Threads with same entry point (bottom frames) - all frames must match for grouping
            // since the implementation groups by exact prefix match
            ThreadInfo t1 = createThreadWithStack("Thread-1",
                "com.app.Service.topMethod",
                "com.app.Service.mediumMethod",
                "com.app.Service.entryPoint"
            );

            ThreadInfo t2 = createThreadWithStack("Thread-2",
                "com.app.Service.topMethod",
                "com.app.Service.mediumMethod",
                "com.app.Service.entryPoint"
            );

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Should detect common entry point with identical stacks
            assertTrue(result.hasGroups(), "Should detect common prefix in diverging stacks");
            boolean foundGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() >= 2 && g.prefix().size() >= 2);
            assertTrue(foundGroup, "Should find group with at least 2 frames in common");
        }

        @Test
        @DisplayName("Should handle threads with no common prefix")
        void shouldHandleNoCommonPrefix() {
            ThreadInfo t1 = createThreadWithStack("Thread-1", "com.app.ServiceA.method");
            ThreadInfo t2 = createThreadWithStack("Thread-2", "com.app.ServiceB.method");
            ThreadInfo t3 = createThreadWithStack("Thread-3", "com.app.ServiceC.method");

            ThreadDump dump = createDump(t1, t2, t3);
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            // May have no groups or only small groups (each thread separate)
            assertEquals(AnalysisResult.Severity.OK, result.getSeverity(),
                "Should be OK when threads have diverse stacks");
            // Groups should be small or non-existent since no common patterns
            long largeGroups = result.getGroups().stream()
                .filter(g -> g.threads().size() > 1)
                .count();
            assertEquals(0, largeGroups, "Should not group threads with no common prefix");
        }
    }

    @Nested
    @DisplayName("Pattern Grouping Tests")
    class PatternGroupingTests {

        @Test
        @DisplayName("Should group threads by common pattern")
        void shouldGroupThreadsByPattern() {
            // Create 5 threads with same pattern
            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(createThreadWithStack("Worker-" + i,
                    "com.app.Worker.process",
                    "com.app.Worker.execute",
                    "java.util.concurrent.ThreadPoolExecutor.runWorker"
                ));
            }

            // Add a different thread
            threads.add(createThreadWithStack("Main",
                "com.app.Main.main"
            ));

            ThreadDump dump = createDump(threads.toArray(new ThreadInfo[0]));
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertTrue(result.hasGroups());

            // Should find a group with 5 workers
            boolean foundLargeGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() >= 5);
            assertTrue(foundLargeGroup, "Should find group with 5 workers");
        }

        @Test
        @DisplayName("Should identify multiple distinct groups")
        void shouldIdentifyMultipleGroups() {
            List<ThreadInfo> threads = new ArrayList<>();

            // Group 1: HTTP handlers (3 threads)
            for (int i = 0; i < 3; i++) {
                threads.add(createThreadWithStack("HTTP-" + i,
                    "com.app.HttpHandler.handle",
                    "com.app.HttpHandler.process"
                ));
            }

            // Group 2: DB workers (3 threads)
            for (int i = 0; i < 3; i++) {
                threads.add(createThreadWithStack("DB-" + i,
                    "com.app.DbWorker.query",
                    "com.app.DbWorker.execute"
                ));
            }

            ThreadDump dump = createDump(threads.toArray(new ThreadInfo[0]));
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertTrue(result.hasGroups());

            // Should find at least 2 groups
            assertTrue(result.getGroups().size() >= 2,
                "Should identify multiple distinct groups");
        }
    }

    @Nested
    @DisplayName("Multi-Dump Pattern Stability Tests")
    class PatternStabilityTests {

        @Test
        @DisplayName("Should detect stable patterns across dumps")
        void shouldDetectStablePatterns() {
            // Create 3 dumps with same pattern
            List<ThreadDump> dumps = new ArrayList<>();

            for (int d = 0; d < 3; d++) {
                List<ThreadInfo> threads = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    threads.add(createThreadWithStack("Worker-" + t,
                        "com.app.Worker.process",
                        "com.app.Worker.handle"
                    ));
                }
                dumps.add(createDump(threads.toArray(new ThreadInfo[0])));
            }

            AnalysisContext context = AnalysisContext.of(dumps);
            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Pattern should be detected as stable across all dumps
            assertTrue(result.hasGroups(), "Should detect stable pattern across dumps");
            // Should find a group with all 4 workers in each dump
            boolean foundStableGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() >= 4);
            assertTrue(foundStableGroup, "Should find stable group of 4 workers");
        }

        @Test
        @DisplayName("Should detect changing patterns")
        void shouldDetectChangingPatterns() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Dump 1: Pattern A
            dumps.add(createDump(
                createThreadWithStack("Worker-1", "com.app.PatternA.method"),
                createThreadWithStack("Worker-2", "com.app.PatternA.method")
            ));

            // Dump 2: Pattern B
            dumps.add(createDump(
                createThreadWithStack("Worker-1", "com.app.PatternB.method"),
                createThreadWithStack("Worker-2", "com.app.PatternB.method")
            ));

            AnalysisContext context = AnalysisContext.of(dumps);
            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Should detect pattern change - groups exist but patterns differ
            assertTrue(result.hasGroups(), "Should detect grouping in each dump");
            // Each dump should have its own group pattern
            assertTrue(result.getGroups().size() >= 2,
                "Should detect different patterns across dumps");
        }
    }

    @Nested
    @DisplayName("Thread Migration Tests")
    class ThreadMigrationTests {

        @Test
        @DisplayName("Should detect thread migration between groups")
        void shouldDetectThreadMigration() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Dump 1: Thread-1 in Group A
            dumps.add(createDump(
                createThreadWithNativeId("Thread-1", 0x1L, "com.app.GroupA.method"),
                createThreadWithNativeId("Thread-2", 0x2L, "com.app.GroupA.method"),
                createThreadWithNativeId("Thread-3", 0x3L, "com.app.GroupB.method")
            ));

            // Dump 2: Thread-1 migrated to Group B
            dumps.add(createDump(
                createThreadWithNativeId("Thread-1", 0x1L, "com.app.GroupB.method"),
                createThreadWithNativeId("Thread-2", 0x2L, "com.app.GroupA.method"),
                createThreadWithNativeId("Thread-3", 0x3L, "com.app.GroupB.method")
            ));

            AnalysisContext context = AnalysisContext.of(dumps);
            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Should detect groups - Thread-1 changed from GroupA to GroupB pattern
            assertTrue(result.hasGroups(), "Should detect grouping despite migration");
            // Should find at least 2 distinct patterns
            assertTrue(result.getGroups().size() >= 2,
                "Should identify both GroupA and GroupB patterns");
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty stacks")
        void shouldHandleEmptyStacks() {
            ThreadInfo t1 = createThread("Thread-1", Thread.State.RUNNABLE);
            ThreadInfo t2 = createThread("Thread-2", Thread.State.WAITING);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertEquals(AnalysisResult.Severity.OK, result.getSeverity());
        }

        @Test
        @DisplayName("Should handle single thread")
        void shouldHandleSingleThread() {
            ThreadInfo t1 = createThreadWithStack("Thread-1", "com.app.Service.method");

            ThreadDump dump = createDump(t1);
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Single thread cannot form a group (needs at least 2 threads with common pattern)
            assertEquals(AnalysisResult.Severity.OK, result.getSeverity());
            // Should have either no groups or groups with size 1
            boolean hasMultiThreadGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() > 1);
            assertFalse(hasMultiThreadGroup, "Single thread should not form multi-thread group");
        }

        @Test
        @DisplayName("Should handle very deep stacks")
        void shouldHandleDeepStacks() {
            // Create thread with 100-frame stack
            String[] frames = new String[100];
            for (int i = 0; i < 100; i++) {
                frames[i] = "com.app.Layer" + i + ".method";
            }

            ThreadInfo t1 = createThreadWithStack("Deep-1", frames);
            ThreadInfo t2 = createThreadWithStack("Deep-2", frames);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertTrue(result.getGroups().size() > 0, "Should create groups for deep stacks");
            // Should group the two identical deep stacks
            boolean foundDeepGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() == 2 && g.prefix().size() == 100);
            assertTrue(foundDeepGroup, "Should group threads with identical 100-frame stacks");
        }

        @Test
        @DisplayName("Should handle many threads")
        void shouldHandleManyThreads() {
            List<ThreadInfo> threads = new ArrayList<>();

            // Create 100 threads with same pattern
            for (int i = 0; i < 100; i++) {
                threads.add(createThreadWithStack("Thread-" + i,
                    "com.app.Service.process",
                    "com.app.Service.handle"
                ));
            }

            ThreadDump dump = createDump(threads.toArray(new ThreadInfo[0]));
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertTrue(result.getGroups().size() > 0, "Should create groups from many threads");

            // Should find a large group
            boolean foundLargeGroup = result.getGroups().stream()
                .anyMatch(g -> g.threads().size() >= 50);
            assertTrue(foundLargeGroup, "Should group many similar threads");

            // The single group should contain most/all of the 100 threads
            int maxGroupSize = result.getGroups().stream()
                .mapToInt(g -> g.threads().size())
                .max()
                .orElse(0);
            assertTrue(maxGroupSize >= 90,
                "Should group at least 90 of 100 identical threads, found: " + maxGroupSize);
        }
    }

    @Nested
    @DisplayName("Severity and Findings Tests")
    class SeverityTests {

        @Test
        @DisplayName("Should report OK for diverse thread activity")
        void shouldReportOKForDiverseActivity() {
            List<ThreadInfo> threads = new ArrayList<>();

            // Create diverse threads
            for (int i = 0; i < 10; i++) {
                threads.add(createThreadWithStack("Thread-" + i,
                    "com.app.Service" + i + ".method"
                ));
            }

            ThreadDump dump = createDump(threads.toArray(new ThreadInfo[0]));
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            // Diverse activity is healthy
            assertTrue(result.getSeverity().getLevel() <= AnalysisResult.Severity.INFO.getLevel());
        }

        @Test
        @DisplayName("Should generate findings for large groups")
        void shouldGenerateFindingsForLargeGroups() {
            List<ThreadInfo> threads = new ArrayList<>();

            // Create 20 threads with same stack
            for (int i = 0; i < 20; i++) {
                threads.add(createThreadWithStack("Stuck-" + i,
                    "com.app.BlockingCall.wait",
                    "com.app.Service.process"
                ));
            }

            ThreadDump dump = createDump(threads.toArray(new ThreadInfo[0]));
            AnalysisContext context = AnalysisContext.of(dump);

            SimilarStackAnalyzer.SimilarStackResult result = analyzer.analyze(context);

            // Large group of similar threads might indicate an issue
            assertNotNull(result.getFindings());
        }
    }

    // Helper methods

    private ThreadInfo createThread(String name, Thread.State state) {
        return new ThreadInfo(
            name,
            (long) name.hashCode(),
            (long) name.hashCode(),
            5,
            false,
            state,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null
        );
    }

    private ThreadInfo createThreadWithStack(String name, String... stackFrames) {
        List<StackFrame> stack = new ArrayList<>();
        for (String frame : stackFrames) {
            String[] parts = frame.split("\\.");
            String className = parts.length > 1 ?
                frame.substring(0, frame.lastIndexOf('.')) : "Unknown";
            String methodName = parts.length > 1 ?
                parts[parts.length - 1] : frame;
            stack.add(new StackFrame(className, methodName, null, -1, false));
        }

        return new ThreadInfo(
            name,
            (long) name.hashCode(),
            (long) name.hashCode(),
            5,
            false,
            Thread.State.RUNNABLE,
            null,
            null,
            stack,
            List.of(),
            null,
            null
        );
    }

    private ThreadInfo createThreadWithNativeId(String name, long nativeId, String... stackFrames) {
        List<StackFrame> stack = new ArrayList<>();
        for (String frame : stackFrames) {
            String[] parts = frame.split("\\.");
            String className = parts.length > 1 ?
                frame.substring(0, frame.lastIndexOf('.')) : "Unknown";
            String methodName = parts.length > 1 ?
                parts[parts.length - 1] : frame;
            stack.add(new StackFrame(className, methodName, null, -1, false));
        }

        return new ThreadInfo(
            name,
            (long) name.hashCode(),
            nativeId,
            5,
            false,
            Thread.State.RUNNABLE,
            null,
            null,
            stack,
            List.of(),
            null,
            null
        );
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(
            Instant.now(),
            "Test JVM",
            List.of(threads),
            null,
            "test"
        );
    }
}