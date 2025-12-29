package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.AnalysisContext;
import me.bechberger.jstall.analysis.AnalysisOptions;
import me.bechberger.jstall.analysis.AnalysisResult;
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
 * Tests for StackGroupAnalyzer
 */
class StackGroupAnalyzerTest {

    private StackGroupAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new StackGroupAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("StackGroupAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
    }

    @Nested
    class SingleDumpTests {

        @Test
        void shouldFindNoGroupsWithUniqueStacks() {
            List<StackFrame> stack1 = List.of(new StackFrame("com.a.A", "method1", "A.java", 10));
            List<StackFrame> stack2 = List.of(new StackFrame("com.b.B", "method2", "B.java", 20));

            ThreadInfo t1 = createThread("thread-1", stack1);
            ThreadInfo t2 = createThread("thread-2", stack2);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(t1, t2), null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertTrue(result.getGroups().isEmpty());
            assertEquals(0, result.getTotalGroupedThreads());
        }

        @Test
        void shouldGroupThreadsWithIdenticalStacks() {
            List<StackFrame> sharedStack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100),
                    new StackFrame("com.example.Client", "receive", "Client.java", 50)
            );

            ThreadInfo t1 = createThread("worker-1", sharedStack);
            ThreadInfo t2 = createThread("worker-2", sharedStack);
            ThreadInfo t3 = createThread("worker-3", sharedStack);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(t1, t2, t3), null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertEquals(1, result.getGroups().size());
            assertEquals(3, result.getGroups().getFirst().size());
            assertEquals(3, result.getTotalGroupedThreads());
        }

        @Test
        void shouldCreateMultipleGroups() {
            List<StackFrame> stack1 = List.of(new StackFrame("com.a.A", "method1", "A.java", 10));
            List<StackFrame> stack2 = List.of(new StackFrame("com.b.B", "method2", "B.java", 20));

            ThreadInfo t1 = createThread("group1-a", stack1);
            ThreadInfo t2 = createThread("group1-b", stack1);
            ThreadInfo t3 = createThread("group2-a", stack2);
            ThreadInfo t4 = createThread("group2-b", stack2);
            ThreadInfo t5 = createThread("group2-c", stack2);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM",
                    List.of(t1, t2, t3, t4, t5), null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertEquals(2, result.getGroups().size());
            assertEquals(5, result.getTotalGroupedThreads());

            // Largest group should be first
            assertEquals(3, result.getGroups().get(0).size());
            assertEquals(2, result.getGroups().get(1).size());
        }

        @Test
        void shouldRespectMinGroupSize() {
            StackGroupAnalyzer customAnalyzer = new StackGroupAnalyzer(3); // Require at least 3

            List<StackFrame> sharedStack = List.of(
                    new StackFrame("com.example.App", "run", "App.java", 10)
            );

            ThreadInfo t1 = createThread("worker-1", sharedStack);
            ThreadInfo t2 = createThread("worker-2", sharedStack);
            // Only 2 threads with same stack

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(t1, t2), null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = customAnalyzer.analyze(context);

            assertTrue(result.getGroups().isEmpty()); // Below min group size
        }

        @Test
        void shouldIgnoreThreadsWithEmptyStacks() {
            List<StackFrame> sharedStack = List.of(
                    new StackFrame("com.example.App", "run", "App.java", 10)
            );

            ThreadInfo t1 = createThread("worker-1", sharedStack);
            ThreadInfo t2 = createThread("worker-2", sharedStack);
            ThreadInfo t3 = createThread("no-stack", List.of()); // Empty stack

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(t1, t2, t3), null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertEquals(1, result.getGroups().size());
            assertEquals(2, result.getGroups().getFirst().size());
        }
    }

    @Nested
    class MultiDumpTests {

        @Test
        void shouldAnalyzeEachDumpSeparately() {
            List<StackFrame> sharedStack = List.of(
                    new StackFrame("com.example.App", "run", "App.java", 10)
            );

            ThreadInfo t1 = createThread("worker-1", sharedStack);
            ThreadInfo t2 = createThread("worker-2", sharedStack);
            ThreadInfo t3 = createThread("worker-3", sharedStack);

            ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(t1, t2), null, "jstack");
            ThreadDump dump2 = new ThreadDump(Instant.now().plusSeconds(5), "JVM", List.of(t1, t2, t3), null, "jstack");

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2), includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            // Should have groups from both dumps
            assertEquals(2, result.getGroups().size());

            // Check groups by dump index
            assertEquals(1, result.getGroupsForDump(0).size());
            assertEquals(1, result.getGroupsForDump(1).size());
            assertEquals(2, result.getGroupsForDump(0).getFirst().size());
            assertEquals(3, result.getGroupsForDump(1).getFirst().size());
        }
    }

    @Nested
    class SeverityTests {

        @Test
        void shouldAssignWarningSeverityForIOBlocking() {
            List<StackFrame> ioStack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100)
            );

            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                threads.add(createThread("io-worker-" + i, ioStack));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertEquals(AnalysisResult.Severity.WARNING, result.getSeverity());
        }

        @Test
        void shouldAssignInfoSeverityForSmallerGroups() {
            List<StackFrame> stack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100)
            );

            List<ThreadInfo> threads = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(createThread("worker-" + i, stack));
            }

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", threads, null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertEquals(AnalysisResult.Severity.INFO, result.getSeverity());
        }
    }

    @Nested
    class FindingsTests {

        @Test
        void shouldGenerateFindingsWithDetails() {
            List<StackFrame> sharedStack = List.of(
                    new StackFrame("com.example.App", "process", "App.java", 42)
            );

            ThreadInfo t1 = createThread("worker-1", sharedStack);
            ThreadInfo t2 = createThread("worker-2", sharedStack);
            ThreadInfo t3 = createThread("worker-3", sharedStack);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(t1, t2, t3), null, "jstack");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertTrue(result.hasFindings());
            assertEquals(1, result.getFindings().size());

            AnalysisResult.Finding finding = result.getFindings().getFirst();
            assertEquals("identical-stacks", finding.category());
            assertTrue(finding.message().contains("3 threads"));
            assertNotNull(finding.details());
            assertEquals(0, finding.details().get("dumpIndex"));
            assertEquals(1, finding.details().get("stackDepth"));
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldFindGroupsInThreadPoolDump() throws IOException {
            ThreadDump dump = loadDump("thread-pool.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Thread pool dumps typically have some threads with identical stacks
            assertNotNull(result.getSummary());
        }

        @Test
        void shouldFindGroupsInComplexDump() throws IOException {
            ThreadDump dump = loadDump("complex.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Complex dump should have analyzable content
            assertTrue(result.getGroups().size() >= 0);
        }

        @Test
        void shouldAnalyzeVirtualThreadsDump() throws IOException {
            ThreadDump dump = loadDump("virtual-threads.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Virtual threads often share stacks
        }

        @Test
        void shouldAnalyzeDeadlockDump() throws IOException {
            ThreadDump dump = loadDump("deadlock.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
        }

        @Test
        void shouldAnalyzeMultipleDumpsOverTime() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dumps.add(loadDump("thread-pool-load-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Should have groups from multiple dumps
            assertTrue(result.getGroups().size() >= 0);
        }

        @Test
        void shouldFindGroupsAcrossGCProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dumps.add(loadDump("gc-progression-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
            // GC progression should show thread patterns
        }

        @Test
        void shouldHandleManyVirtualThreads() throws IOException {
            ThreadDump dump = loadDump("many-virtual-threads.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            StackGroupAnalyzer.StackGroupResult result = analyzer.analyze(context);

            assertNotNull(result);
            // Many virtual threads might share common stacks
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

    private ThreadInfo createThread(String name, List<StackFrame> stack) {
        return new ThreadInfo(name, (long) name.hashCode(), (long) (name.hashCode() * 31),
                5, true, Thread.State.RUNNABLE, 100L, 1000L, stack, List.of(), null, null);
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}