package me.bechberger.jstall.analysis;

import me.bechberger.jstall.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AnalysisContext
 */
class AnalysisContextTest {

    private ThreadDump simpleDump;
    private ThreadDump complexDump;

    @BeforeEach
    void setUp() {
        simpleDump = createSimpleDump();
        complexDump = createComplexDump();
    }

    @Nested
    class SingleDumpTests {

        @Test
        void shouldCreateContextFromSingleDump() {
            AnalysisContext context = AnalysisContext.of(simpleDump);

            assertTrue(context.isSingleDump());
            assertFalse(context.isMultiDump());
            assertEquals(1, context.getDumpCount());
            assertSame(simpleDump, context.getFirstDump());
            assertSame(simpleDump, context.getLastDump());
            assertFalse(context.hasJfr());
        }

        @Test
        void shouldRejectNullDump() {
            assertThrows(NullPointerException.class, () ->
                    AnalysisContext.of((ThreadDump) null));
        }
    }

    @Nested
    class MultiDumpTests {

        @Test
        void shouldCreateContextFromMultipleDumps() {
            List<ThreadDump> dumps = List.of(simpleDump, complexDump);
            AnalysisContext context = AnalysisContext.of(dumps);

            assertFalse(context.isSingleDump());
            assertTrue(context.isMultiDump());
            assertEquals(2, context.getDumpCount());
            assertSame(simpleDump, context.getFirstDump());
            assertSame(complexDump, context.getLastDump());
        }

        @Test
        void shouldRejectEmptyDumpList() {
            assertThrows(IllegalArgumentException.class, () ->
                    AnalysisContext.of(List.of()));
        }
    }

    @Nested
    class ThreadMatchingTests {

        @Test
        void shouldFindThreadByNativeId() {
            AnalysisContext context = AnalysisContext.of(simpleDump);

            List<ThreadInfo> found = context.findThreadByNativeId(0x1234L);
            assertEquals(1, found.size());
            assertEquals("main", found.getFirst().name());
        }

        @Test
        void shouldFindThreadByName() {
            AnalysisContext context = AnalysisContext.of(simpleDump);

            List<ThreadInfo> found = context.findThreadByName("main");
            assertEquals(1, found.size());
            assertEquals(0x1234L, found.getFirst().nativeId());
        }

        @Test
        void shouldMatchThreadAcrossDumps() {
            ThreadInfo mainThread1 = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
            ThreadInfo mainThread2 = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 150L, 1500L);

            ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(mainThread1), null, "jstack");
            ThreadDump dump2 = new ThreadDump(Instant.now().plusSeconds(5), "JVM", List.of(mainThread2), null, "jstack");

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2));

            ThreadInfo matched = context.matchThread(mainThread1, dump2);
            assertNotNull(matched);
            assertEquals(mainThread2, matched);
        }

        @Test
        void shouldGetMatchedThreadsAcrossDumps() {
            ThreadInfo main1 = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
            ThreadInfo worker1 = createThread("worker-1", 2L, 0x5678L, Thread.State.WAITING, 50L, 500L);

            ThreadInfo main2 = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 150L, 1500L);
            // worker-1 is gone in dump2
            ThreadInfo worker2 = createThread("worker-2", 3L, 0x9ABCL, Thread.State.RUNNABLE, 10L, 100L);

            ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(main1, worker1), null, "jstack");
            ThreadDump dump2 = new ThreadDump(Instant.now().plusSeconds(5), "JVM", List.of(main2, worker2), null, "jstack");

            AnalysisContext context = AnalysisContext.of(List.of(dump1, dump2));
            var matched = context.getMatchedThreads();

            // Should have 3 unique threads
            assertEquals(3, matched.size());

            // main should appear in both dumps
            var mainId = AnalysisContext.ThreadIdentifier.of(main1);
            assertTrue(matched.containsKey(mainId));
            List<ThreadInfo> mainHistory = matched.get(mainId);
            assertEquals(main1, mainHistory.get(0));
            assertEquals(main2, mainHistory.get(1));
        }
    }

    @Nested
    class FilteringTests {

        @Test
        void shouldFilterDaemonThreadsByDefault() {
            ThreadInfo daemonThread = createDaemonThread("pool-1-thread-1");
            ThreadInfo userThread = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM",
                    List.of(daemonThread, userThread), null, "jstack");

            AnalysisContext context = AnalysisContext.of(dump);

            List<ThreadInfo> filtered = context.getFilteredThreads(dump);
            assertEquals(1, filtered.size());
            assertEquals("main", filtered.getFirst().name());
        }

        @Test
        void shouldIncludeDaemonThreadsWhenEnabled() {
            ThreadInfo daemonThread = createDaemonThread("pool-1-thread-1");
            ThreadInfo userThread = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM",
                    List.of(daemonThread, userThread), null, "jstack");

            AnalysisOptions options = AnalysisOptions.builder()
                    .includeDaemon(true)
                    .build();

            AnalysisContext context = AnalysisContext.of(dump, options);

            List<ThreadInfo> filtered = context.getFilteredThreads(dump);
            assertEquals(2, filtered.size());
        }

        @Test
        void shouldFilterByIgnorePattern() {
            ThreadInfo main = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
            ThreadInfo worker = createThread("worker-1", 2L, 0x5678L, Thread.State.RUNNABLE, 50L, 500L);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM",
                    List.of(main, worker), null, "jstack");

            AnalysisOptions options = AnalysisOptions.builder()
                    .addIgnorePattern("worker-.*")
                    .build();

            AnalysisContext context = AnalysisContext.of(dump, options);

            List<ThreadInfo> filtered = context.getFilteredThreads(dump);
            assertEquals(1, filtered.size());
            assertEquals("main", filtered.getFirst().name());
        }

        @Test
        void shouldFilterByFocusPattern() {
            ThreadInfo main = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
            ThreadInfo worker1 = createThread("worker-1", 2L, 0x5678L, Thread.State.RUNNABLE, 50L, 500L);
            ThreadInfo worker2 = createThread("worker-2", 3L, 0x9ABCL, Thread.State.RUNNABLE, 30L, 300L);

            ThreadDump dump = new ThreadDump(Instant.now(), "JVM",
                    List.of(main, worker1, worker2), null, "jstack");

            AnalysisOptions options = AnalysisOptions.builder()
                    .addFocusPattern("worker-.*")
                    .includeDaemon(true) // Ensure daemon filter doesn't interfere
                    .build();

            AnalysisContext context = AnalysisContext.of(dump, options);

            List<ThreadInfo> filtered = context.getFilteredThreads(dump);
            assertEquals(2, filtered.size());
            assertTrue(filtered.stream().allMatch(t -> t.name().startsWith("worker-")));
        }
    }

    @Nested
    class ThreadIdentifierTests {

        @Test
        void shouldMatchByNativeIdFirst() {
            var id1 = new AnalysisContext.ThreadIdentifier(0x1234L, 1L, "main");
            var id2 = new AnalysisContext.ThreadIdentifier(0x1234L, 2L, "different");

            assertEquals(id1, id2); // Same native ID
        }

        @Test
        void shouldMatchByThreadIdIfNoNativeId() {
            var id1 = new AnalysisContext.ThreadIdentifier(null, 1L, "main");
            var id2 = new AnalysisContext.ThreadIdentifier(null, 1L, "different");

            assertEquals(id1, id2); // Same thread ID
        }

        @Test
        void shouldMatchByNameIfNoIds() {
            var id1 = new AnalysisContext.ThreadIdentifier(null, null, "main");
            var id2 = new AnalysisContext.ThreadIdentifier(null, null, "main");

            assertEquals(id1, id2); // Same name
        }

        @Test
        void shouldNotMatchDifferentThreads() {
            var id1 = new AnalysisContext.ThreadIdentifier(0x1234L, 1L, "main");
            var id2 = new AnalysisContext.ThreadIdentifier(0x5678L, 2L, "worker");

            assertNotEquals(id1, id2);
        }
    }

    // Helper methods

    private ThreadDump createSimpleDump() {
        ThreadInfo main = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 100L, 1000L);
        return new ThreadDump(Instant.now(), "JVM Info", List.of(main), null, "jstack");
    }

    private ThreadDump createComplexDump() {
        ThreadInfo main = createThread("main", 1L, 0x1234L, Thread.State.RUNNABLE, 200L, 2000L);
        ThreadInfo worker = createThread("worker-1", 2L, 0x5678L, Thread.State.BLOCKED, 50L, 500L);
        return new ThreadDump(Instant.now().plusSeconds(10), "JVM Info", List.of(main, worker), null, "jstack");
    }

    private ThreadInfo createThread(String name, Long threadId, Long nativeId,
                                    Thread.State state, Long cpuTimeMs, Long elapsedTimeMs) {
        List<StackFrame> stack = List.of(
                new StackFrame("com.example.App", "run", "App.java", 42)
        );
        return new ThreadInfo(name, threadId, nativeId, 5, false, state,
                cpuTimeMs, elapsedTimeMs, stack, List.of(), null, null);
    }

    private ThreadInfo createDaemonThread(String name) {
        return new ThreadInfo(name, 10L, 0xABCDL, 5, true, Thread.State.WAITING,
                10L, 100L, List.of(), List.of(), null, null);
    }
}