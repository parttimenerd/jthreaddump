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
 * Tests for GCActivityAnalyzer
 */
class GCActivityAnalyzerTest {

    private GCActivityAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new GCActivityAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("GCActivityAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
    }

    @Nested
    class SingleDumpTests {

        @Test
        void shouldAnalyzeDumpWithoutGCThreads() {
            ThreadInfo t1 = createThread("main", 0x1L, Thread.State.RUNNABLE, 1000L);
            ThreadInfo t2 = createThread("worker", 0x2L, Thread.State.RUNNABLE, 500L);

            ThreadDump dump = createDump(t1, t2);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            GCActivityAnalyzer.GCActivityResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertEquals(0, result.getSnapshots().getFirst().gcThreadCount());
        }

        @Test
        void shouldDetectGCThreads() {
            ThreadInfo main = createThread("main", 0x1L, Thread.State.RUNNABLE, 1000L);
            ThreadInfo gc1 = createThread("GC Thread#0", 0x10L, Thread.State.RUNNABLE, 500L);
            ThreadInfo gc2 = createThread("GC Thread#1", 0x11L, Thread.State.RUNNABLE, 500L);
            ThreadInfo g1 = createThread("G1 Main Marker", 0x20L, Thread.State.RUNNABLE, 100L);

            ThreadDump dump = createDump(main, gc1, gc2, g1);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            GCActivityAnalyzer.GCActivityResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertEquals(3, result.getSnapshots().getFirst().gcThreadCount());
        }

        @Test
        void shouldCalculateGCCpuPercentage() {
            ThreadInfo main = createThread("main", 0x1L, Thread.State.RUNNABLE, 8000L);
            ThreadInfo gc = createThread("GC Thread#0", 0x10L, Thread.State.RUNNABLE, 2000L);

            ThreadDump dump = createDump(main, gc);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            GCActivityAnalyzer.GCActivityResult result = analyzer.analyze(context);

            // GC: 2000ms, App: 8000ms, Total: 10000ms, GC%: 20%
            assertEquals(20.0, result.getSnapshots().getFirst().gcCpuPercentage(), 0.1);
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldAnalyzeGCActivityDump() throws IOException {
            ThreadDump dump = loadDump("gc-activity.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            GCActivityAnalyzer.GCActivityResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertNotNull(result.getGCSummary());
        }

        @Test
        void shouldAnalyzeGCProgression() throws IOException {
            List<ThreadDump> dumps = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                dumps.add(loadDump("gc-over-time-" + i + ".txt"));
            }

            AnalysisContext context = AnalysisContext.of(dumps, includeAll());

            GCActivityAnalyzer.GCActivityResult result = analyzer.analyze(context);

            assertNotNull(result);
            assertEquals(3, result.getSnapshots().size());
        }

        @Test
        void shouldAnalyzeComplexDump() throws IOException {
            ThreadDump dump = loadDump("complex.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            GCActivityAnalyzer.GCActivityResult result = analyzer.analyze(context);

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

    private ThreadInfo createThread(String name, Long nid, Thread.State state, Long cpuTimeMs) {
        return new ThreadInfo(name, 1L, nid, 5, true, state,
                cpuTimeMs, cpuTimeMs * 10, List.of(), List.of(), null, null);
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(Instant.now(), "JVM", List.of(threads), null, "jstack");
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}