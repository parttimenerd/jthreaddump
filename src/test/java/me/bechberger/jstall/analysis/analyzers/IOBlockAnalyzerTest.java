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
 * Tests for IOBlockAnalyzer
 */
class IOBlockAnalyzerTest {

    private IOBlockAnalyzer analyzer;
    private ThreadDumpParser parser;

    @BeforeEach
    void setUp() {
        analyzer = new IOBlockAnalyzer();
        parser = new ThreadDumpParser();
    }

    @Test
    void shouldHaveCorrectMetadata() {
        assertEquals("IOBlockAnalyzer", analyzer.getName());
        assertFalse(analyzer.getDescription().isEmpty());
        assertFalse(analyzer.requiresMultipleDumps());
        assertFalse(analyzer.requiresJfr());
    }

    @Nested
    class SingleDumpTests {

        @Test
        void shouldDetectSocketRead() {
            List<StackFrame> stack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100),
                    new StackFrame("com.example.Client", "receive", "Client.java", 50)
            );
            ThreadInfo t = createThreadWithStack("socket-reader", 0x1L, Thread.State.RUNNABLE, stack);

            ThreadDump dump = createDump(t);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertFalse(result.getBlockedThreads().isEmpty());
            assertEquals(IOBlockAnalyzer.IOType.SOCKET_READ,
                    result.getBlockedThreads().getFirst().ioType());
        }

        @Test
        void shouldDetectFileRead() {
            List<StackFrame> stack = List.of(
                    new StackFrame("java.io.FileInputStream", "readBytes", "FileInputStream.java", 100),
                    new StackFrame("com.example.Reader", "read", "Reader.java", 50)
            );
            ThreadInfo t = createThreadWithStack("file-reader", 0x1L, Thread.State.RUNNABLE, stack);

            ThreadDump dump = createDump(t);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertFalse(result.getBlockedThreads().isEmpty());
            assertEquals(IOBlockAnalyzer.IOType.FILE_READ,
                    result.getBlockedThreads().getFirst().ioType());
        }

        @Test
        void shouldDetectDatabaseIO() {
            List<StackFrame> stack = List.of(
                    new StackFrame("com.mysql.cj.protocol.a.NativeProtocol", "read", "NativeProtocol.java", 100),
                    new StackFrame("com.example.Dao", "query", "Dao.java", 50)
            );
            ThreadInfo t = createThreadWithStack("db-query", 0x1L, Thread.State.RUNNABLE, stack);

            ThreadDump dump = createDump(t);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertFalse(result.getBlockedThreads().isEmpty());
            assertEquals(IOBlockAnalyzer.IOType.DATABASE,
                    result.getBlockedThreads().getFirst().ioType());
        }

        @Test
        void shouldNotDetectIOInNormalThread() {
            List<StackFrame> stack = List.of(
                    new StackFrame("com.example.Service", "compute", "Service.java", 100),
                    new StackFrame("com.example.Main", "run", "Main.java", 50)
            );
            ThreadInfo t = createThreadWithStack("worker", 0x1L, Thread.State.RUNNABLE, stack);

            ThreadDump dump = createDump(t);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertTrue(result.getBlockedThreads().isEmpty());
        }

        @Test
        void shouldCountByType() {
            List<StackFrame> socketStack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100)
            );
            List<StackFrame> fileStack = List.of(
                    new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)
            );

            ThreadInfo t1 = createThreadWithStack("socket-1", 0x1L, Thread.State.RUNNABLE, socketStack);
            ThreadInfo t2 = createThreadWithStack("socket-2", 0x2L, Thread.State.RUNNABLE, socketStack);
            ThreadInfo t3 = createThreadWithStack("file-1", 0x3L, Thread.State.RUNNABLE, fileStack);

            ThreadDump dump = createDump(t1, t2, t3);
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertEquals(3, result.getBlockedThreads().size());
            assertEquals(2, result.getByType(IOBlockAnalyzer.IOType.SOCKET_READ).size());
            assertEquals(1, result.getByType(IOBlockAnalyzer.IOType.FILE_READ).size());
        }
    }

    @Nested
    class RealThreadDumpTests {

        @Test
        void shouldAnalyzeThreadPoolDump() throws IOException {
            ThreadDump dump = loadDump("thread-pool.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertNotNull(result);
        }

        @Test
        void shouldAnalyzeVirtualThreadsDump() throws IOException {
            ThreadDump dump = loadDump("virtual-threads.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertNotNull(result);
        }

        @Test
        void shouldAnalyzeComplexDump() throws IOException {
            ThreadDump dump = loadDump("complex.txt");
            AnalysisContext context = AnalysisContext.of(dump, includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            assertNotNull(result);
        }
    }

    @Nested
    class MultiDumpTests {

        @Test
        void shouldDetectStuckIOThread() {
            List<StackFrame> socketStack = List.of(
                    new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100)
            );

            ThreadInfo t = createThreadWithStack("stuck-reader", 0x1L, Thread.State.RUNNABLE, socketStack);

            ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(t), null, "jstack");
            ThreadDump dump2 = new ThreadDump(Instant.now().plusSeconds(5), "JVM", List.of(t), null, "jstack");
            ThreadDump dump3 = new ThreadDump(Instant.now().plusSeconds(10), "JVM", List.of(t), null, "jstack");

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2, dump3), includeAll());

            IOBlockAnalyzer.IOBlockResult result = analyzer.analyze(context);

            // Thread appears in I/O in all dumps - should detect as stuck
            assertTrue(result.hasFindings());
            boolean hasStuckFinding = result.getFindings().stream()
                    .anyMatch(f -> "io-stuck".equals(f.category()));
            assertTrue(hasStuckFinding);
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

    private ThreadInfo createThreadWithStack(String name, Long nid, Thread.State state, List<StackFrame> stack) {
        return new ThreadInfo(name, 1L, nid, 5, true, state,
                100L, 1000L, stack, List.of(), null, null);
    }

    private ThreadDump createDump(ThreadInfo... threads) {
        return new ThreadDump(Instant.now(), "JVM", List.of(threads), null, "jstack");
    }

    private AnalysisOptions includeAll() {
        return AnalysisOptions.builder().includeAll().build();
    }
}