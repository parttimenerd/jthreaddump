package me.bechberger.jstall.jfr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JfrAnalyzer
 */
class JfrAnalyzerTest {

    private JfrAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new JfrAnalyzer();
    }

    @Nested
    class MethodProfilingTests {

        @Test
        void shouldFindHottestMethods() {
            List<JfrParser.MethodSample> samples = List.of(
                    createSample("thread-1", "com.example.Hot", "method1"),
                    createSample("thread-1", "com.example.Hot", "method1"),
                    createSample("thread-1", "com.example.Hot", "method1"),
                    createSample("thread-1", "com.example.Cold", "method2")
            );

            JfrParser.JfrData data = createData(samples, List.of(), List.of(), List.of(), List.of());

            List<JfrAnalyzer.MethodProfile> hottest = analyzer.getHottestMethods(data, 10);

            assertFalse(hottest.isEmpty());
            assertEquals("com.example.Hot.method1", hottest.getFirst().method());
            assertEquals(3, hottest.getFirst().sampleCount());
            assertEquals(75.0, hottest.getFirst().percentage(), 0.1);
        }

        @Test
        void shouldFindHottestThreads() {
            List<JfrParser.MethodSample> samples = List.of(
                    createSample("hot-thread", "com.example.A", "m1"),
                    createSample("hot-thread", "com.example.A", "m2"),
                    createSample("hot-thread", "com.example.A", "m3"),
                    createSample("cold-thread", "com.example.B", "m1")
            );

            JfrParser.JfrData data = createData(samples, List.of(), List.of(), List.of(), List.of());

            List<JfrAnalyzer.ThreadProfile> threads = analyzer.getHottestThreads(data, 10);

            assertFalse(threads.isEmpty());
            assertEquals("hot-thread", threads.getFirst().threadName());
            assertEquals(3, threads.getFirst().sampleCount());
        }

        @Test
        void shouldFindMethodsForSpecificThread() {
            List<JfrParser.MethodSample> samples = List.of(
                    createSample("thread-1", "com.example.A", "method1"),
                    createSample("thread-1", "com.example.A", "method1"),
                    createSample("thread-2", "com.example.B", "method2")
            );

            JfrParser.JfrData data = createData(samples, List.of(), List.of(), List.of(), List.of());

            List<JfrAnalyzer.MethodProfile> methods = analyzer.getHottestMethodsForThread(data, "thread-1", 10);

            assertEquals(1, methods.size());
            assertEquals("com.example.A.method1", methods.getFirst().method());
            assertEquals(2, methods.getFirst().sampleCount());
        }

        @Test
        void shouldGetStackProfiles() {
            List<JfrParser.MethodSample> samples = List.of(
                    createSampleWithStack("thread-1", List.of(
                            new JfrParser.StackFrameInfo("com.A", "m1", 10, true),
                            new JfrParser.StackFrameInfo("com.B", "m2", 20, true)
                    )),
                    createSampleWithStack("thread-1", List.of(
                            new JfrParser.StackFrameInfo("com.A", "m1", 10, true),
                            new JfrParser.StackFrameInfo("com.B", "m2", 20, true)
                    ))
            );

            JfrParser.JfrData data = createData(samples, List.of(), List.of(), List.of(), List.of());

            List<JfrAnalyzer.StackProfile> stacks = analyzer.getStackProfiles(data, 10);

            assertFalse(stacks.isEmpty());
            assertEquals(2, stacks.getFirst().sampleCount());
        }
    }

    @Nested
    class LockContentionTests {

        @Test
        void shouldSummarizeLockContention() {
            List<JfrParser.LockEvent> lockEvents = List.of(
                    createLockEvent("thread-1", "java.lang.Object", Duration.ofMillis(100)),
                    createLockEvent("thread-2", "java.lang.Object", Duration.ofMillis(200)),
                    createLockEvent("thread-1", "java.util.HashMap", Duration.ofMillis(50))
            );

            JfrParser.JfrData data = createData(List.of(), lockEvents, List.of(), List.of(), List.of());

            JfrAnalyzer.LockContentionSummary summary = analyzer.getLockContentionSummary(data);

            assertEquals(3, summary.totalEvents());
            assertEquals(2, summary.uniqueLocks());
            assertEquals(350, summary.totalBlockedTime().toMillis());
        }

        @Test
        void shouldFindHotLocks() {
            List<JfrParser.LockEvent> lockEvents = new ArrayList<>();
            // Hot lock - many events
            for (int i = 0; i < 10; i++) {
                lockEvents.add(createLockEvent("thread-" + i, "com.example.HotLock", Duration.ofMillis(100)));
            }
            // Cold lock - few events
            lockEvents.add(createLockEvent("thread-1", "com.example.ColdLock", Duration.ofMillis(10)));

            JfrParser.JfrData data = createData(List.of(), lockEvents, List.of(), List.of(), List.of());

            JfrAnalyzer.LockContentionSummary summary = analyzer.getLockContentionSummary(data);

            assertFalse(summary.hotLocks().isEmpty());
            assertEquals("com.example.HotLock", summary.hotLocks().getFirst().monitorClass());
        }
    }

    @Nested
    class AllocationTests {

        @Test
        void shouldFindAllocationHotspots() {
            List<JfrParser.AllocationEvent> allocations = List.of(
                    createAllocationEvent("thread-1", "com.example.Allocator", "allocate", 1000),
                    createAllocationEvent("thread-1", "com.example.Allocator", "allocate", 1000),
                    createAllocationEvent("thread-1", "com.example.Other", "create", 100)
            );

            JfrParser.JfrData data = createData(List.of(), List.of(), allocations, List.of(), List.of());

            List<JfrAnalyzer.AllocationProfile> hotspots = analyzer.getAllocationHotspots(data, 10);

            assertFalse(hotspots.isEmpty());
            assertEquals("com.example.Allocator.allocate", hotspots.getFirst().allocationSite());
            assertEquals(2000, hotspots.getFirst().totalBytes());
        }

        @Test
        void shouldGetAllocationByThread() {
            List<JfrParser.AllocationEvent> allocations = List.of(
                    createAllocationEvent("hot-allocator", "com.A", "m", 1000),
                    createAllocationEvent("hot-allocator", "com.A", "m", 1000),
                    createAllocationEvent("cold-allocator", "com.B", "m", 100)
            );

            JfrParser.JfrData data = createData(List.of(), List.of(), allocations, List.of(), List.of());

            List<JfrAnalyzer.ThreadAllocationProfile> byThread = analyzer.getAllocationByThread(data, 10);

            assertFalse(byThread.isEmpty());
            assertEquals("hot-allocator", byThread.getFirst().threadName());
            assertEquals(2000, byThread.getFirst().totalBytes());
        }
    }

    @Nested
    class IOAnalysisTests {

        @Test
        void shouldSummarizeIO() {
            List<JfrParser.IOEvent> ioEvents = List.of(
                    createIOEvent("thread-1", JfrParser.IOEventType.FILE_READ, "/file1", 1000, Duration.ofMillis(10)),
                    createIOEvent("thread-1", JfrParser.IOEventType.FILE_WRITE, "/file2", 2000, Duration.ofMillis(20)),
                    createIOEvent("thread-2", JfrParser.IOEventType.SOCKET_READ, "host:8080", 500, Duration.ofMillis(100))
            );

            JfrParser.JfrData data = createData(List.of(), List.of(), List.of(), List.of(), ioEvents);

            JfrAnalyzer.IOSummary summary = analyzer.getIOSummary(data);

            assertEquals(3, summary.totalEvents());
            assertEquals(3500, summary.totalBytes());
            assertEquals(130, summary.totalDuration().toMillis());
        }

        @Test
        void shouldFindIOHotspots() {
            List<JfrParser.IOEvent> ioEvents = List.of(
                    createIOEvent("thread-1", JfrParser.IOEventType.SOCKET_READ, "slow-host:80", 100, Duration.ofMillis(500)),
                    createIOEvent("thread-1", JfrParser.IOEventType.SOCKET_READ, "slow-host:80", 100, Duration.ofMillis(500)),
                    createIOEvent("thread-1", JfrParser.IOEventType.FILE_READ, "/fast-file", 1000, Duration.ofMillis(10))
            );

            JfrParser.JfrData data = createData(List.of(), List.of(), List.of(), List.of(), ioEvents);

            List<JfrAnalyzer.IOTargetProfile> hotspots = analyzer.getIOHotspots(data, 10);

            assertFalse(hotspots.isEmpty());
            assertEquals("slow-host:80", hotspots.getFirst().target());
            assertEquals(1000, hotspots.getFirst().totalDuration().toMillis());
        }

        @Test
        void shouldFindSlowIOOperations() {
            List<JfrParser.IOEvent> ioEvents = List.of(
                    createIOEvent("thread-1", JfrParser.IOEventType.FILE_READ, "/slow", 100, Duration.ofMillis(500)),
                    createIOEvent("thread-1", JfrParser.IOEventType.FILE_READ, "/fast", 100, Duration.ofMillis(10))
            );

            JfrParser.JfrData data = createData(List.of(), List.of(), List.of(), List.of(), ioEvents);

            List<JfrParser.IOEvent> slowOps = analyzer.getSlowIOOperations(data, Duration.ofMillis(100));

            assertEquals(1, slowOps.size());
            assertEquals("/slow", slowOps.getFirst().path());
        }
    }

    @Nested
    class ClassLoadingTests {

        @Test
        void shouldSummarizeClassLoading() {
            List<JfrParser.ClassLoadEvent> classLoadEvents = List.of(
                    createClassLoadEvent("com.example.Class1", "app", Duration.ofMillis(10)),
                    createClassLoadEvent("com.example.Class2", "app", Duration.ofMillis(20)),
                    createClassLoadEvent("java.lang.String", "bootstrap", Duration.ofMillis(5))
            );

            JfrParser.JfrData data = createData(List.of(), List.of(), List.of(), classLoadEvents, List.of());

            JfrAnalyzer.ClassLoadingSummary summary = analyzer.getClassLoadingSummary(data);

            assertEquals(3, summary.totalClasses());
            assertEquals(35, summary.totalDuration().toMillis());
            assertEquals(2, summary.byLoader().size());
        }
    }

    // Helper methods

    private JfrParser.MethodSample createSample(String threadName, String className, String methodName) {
        List<JfrParser.StackFrameInfo> stack = List.of(
                new JfrParser.StackFrameInfo(className, methodName, 10, true)
        );
        return new JfrParser.MethodSample(Instant.now(), threadName, 1L, "RUNNABLE", stack);
    }

    private JfrParser.MethodSample createSampleWithStack(String threadName, List<JfrParser.StackFrameInfo> stack) {
        return new JfrParser.MethodSample(Instant.now(), threadName, 1L, "RUNNABLE", stack);
    }

    private JfrParser.LockEvent createLockEvent(String threadName, String monitorClass, Duration duration) {
        return new JfrParser.LockEvent(Instant.now(), threadName, 1L, monitorClass, duration, null,
                JfrParser.LockEventType.ENTER);
    }

    private JfrParser.AllocationEvent createAllocationEvent(String threadName, String className,
                                                             String methodName, long size) {
        List<JfrParser.StackFrameInfo> stack = List.of(
                new JfrParser.StackFrameInfo(className, methodName, 10, true)
        );
        return new JfrParser.AllocationEvent(Instant.now(), threadName, 1L, "byte[]", size, true, stack);
    }

    private JfrParser.IOEvent createIOEvent(String threadName, JfrParser.IOEventType type,
                                             String target, long bytes, Duration duration) {
        String path = type.isFile() ? target : null;
        String host = type.isSocket() ? target.split(":")[0] : null;
        Integer port = type.isSocket() ? Integer.parseInt(target.split(":")[1]) : null;

        return new JfrParser.IOEvent(Instant.now(), threadName, 1L, type, path, host, port,
                bytes, duration, List.of());
    }

    private JfrParser.ClassLoadEvent createClassLoadEvent(String className, String loader, Duration duration) {
        return new JfrParser.ClassLoadEvent(Instant.now(), className, "main", loader, duration);
    }

    private JfrParser.JfrData createData(List<JfrParser.MethodSample> samples,
                                          List<JfrParser.LockEvent> locks,
                                          List<JfrParser.AllocationEvent> allocations,
                                          List<JfrParser.ClassLoadEvent> classLoads,
                                          List<JfrParser.IOEvent> ioEvents) {
        return new JfrParser.JfrData(null, samples, locks, allocations, classLoads, ioEvents);
    }
}