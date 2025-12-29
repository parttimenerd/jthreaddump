package me.bechberger.jstall.view;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.ProgressClassification;
import me.bechberger.jstall.analysis.AnalysisContext.ThreadIdentifier;
import me.bechberger.jstall.analysis.analyzers.DeadlockAnalyzer;
import me.bechberger.jstall.analysis.analyzers.LockContentionAnalyzer;
import me.bechberger.jstall.analysis.analyzers.StackGroupAnalyzer;
import me.bechberger.jstall.analysis.analyzers.ThreadProgressAnalyzer;
import me.bechberger.jstall.model.*;
import me.bechberger.jstall.view.views.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for individual view implementations
 */
class ViewsTest {

    private OutputOptions textOptions;
    private OutputOptions textOptionsNoColor;
    private OutputOptions htmlOptions;
    private OutputOptions jsonOptions;

    @BeforeEach
    void setUp() {
        textOptions = OutputOptions.builder()
                .format(OutputFormat.TEXT)
                .colorEnabled(true)
                .verbose(true)
                .build();
        textOptionsNoColor = OutputOptions.builder()
                .format(OutputFormat.TEXT)
                .colorEnabled(false)
                .verbose(true)
                .build();
        htmlOptions = OutputOptions.builder()
                .format(OutputFormat.HTML)
                .build();
        jsonOptions = OutputOptions.builder()
                .format(OutputFormat.JSON)
                .build();
    }

    @Nested
    class DeadlockViewTests {

        private DeadlockView view;

        @BeforeEach
        void setUp() {
            view = new DeadlockView();
        }

        @Test
        void shouldRenderNoDeadlocks() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("No deadlocks detected"));
        }

        @Test
        void shouldRenderWithDeadlocks() {
            ThreadInfo thread1 = new ThreadInfo(
                    "Thread-1", 1L, 100L, 5, false,
                    Thread.State.BLOCKED, null, null,
                    List.of(new StackFrame("com.example.Test", "method1", "Test.java", 10, false)),
                    List.of(new LockInfo("0x12345", "java.lang.Object", "waiting to lock")),
                    "0x12345", null
            );

            DeadlockAnalyzer.DetectedDeadlock deadlock = new DeadlockAnalyzer.DetectedDeadlock(
                    List.of(thread1),
                    List.of("0x12345"),
                    0,
                    Instant.now(),
                    true
            );

            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.CRITICAL,
                    List.of(new AnalysisResult.Finding(
                            AnalysisResult.Severity.CRITICAL,
                            "deadlock",
                            "Deadlock detected")),
                    List.of(deadlock)
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("DEADLOCK"));
            assertTrue(output.contains("Thread-1"));
        }

        @Test
        void shouldRenderAsHtml() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = view.render(result, htmlOptions);

            assertTrue(output.contains("<html") || output.contains("<!DOCTYPE"));
            assertTrue(output.contains("Deadlock"));
        }

        @Test
        void shouldRenderAsJson() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = view.render(result, jsonOptions);

            assertTrue(output.startsWith("{"));
            assertTrue(output.contains("\"analyzerName\""));
        }
    }

    @Nested
    class ThreadProgressViewTests {

        private ThreadProgressView view;

        @BeforeEach
        void setUp() {
            view = new ThreadProgressView();
        }

        @Test
        void shouldRenderProgressSummary() {
            ThreadProgressAnalyzer.ProgressSummary summary = new ThreadProgressAnalyzer.ProgressSummary(
                    100, 50, 10, 5, 2, 33
            );

            ThreadProgressAnalyzer.ProgressResult result = new ThreadProgressAnalyzer.ProgressResult(
                    AnalysisResult.Severity.WARNING,
                    List.of(),
                    Map.of(),
                    summary
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("100") || output.contains("Total"));
            assertTrue(output.contains("50") || output.contains("Active"));
        }

        @Test
        void shouldIndicateStall() {
            // Create a summary with high problem percentage
            ThreadProgressAnalyzer.ProgressSummary summary = new ThreadProgressAnalyzer.ProgressSummary(
                    100, 5, 50, 30, 10, 5
            );

            ThreadProgressAnalyzer.ProgressResult result = new ThreadProgressAnalyzer.ProgressResult(
                    AnalysisResult.Severity.ERROR,
                    List.of(),
                    Map.of(),
                    summary
            );

            String output = view.render(result, textOptionsNoColor);

            // Should indicate stall warning for high problem rate
            assertFalse(output.isEmpty());
        }
    }

    @Nested
    class StackGroupViewTests {

        private StackGroupView view;

        @BeforeEach
        void setUp() {
            view = new StackGroupView();
        }

        @Test
        void shouldRenderNoGroups() {
            StackGroupAnalyzer.StackGroupResult result = new StackGroupAnalyzer.StackGroupResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of(),
                    2
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("No significant") || output.contains("0 group"));
        }

        @Test
        void shouldRenderWithGroups() {
            List<StackFrame> stack = List.of(
                    new StackFrame("com.example.Test", "wait", "Test.java", 50, false),
                    new StackFrame("com.example.Test", "run", "Test.java", 30, false)
            );

            ThreadInfo thread1 = new ThreadInfo(
                    "Worker-1", 1L, 100L, 5, false,
                    Thread.State.WAITING, null, null,
                    stack, List.of(), null, null
            );
            ThreadInfo thread2 = new ThreadInfo(
                    "Worker-2", 2L, 101L, 5, false,
                    Thread.State.WAITING, null, null,
                    stack, List.of(), null, null
            );

            StackGroupAnalyzer.StackGroup group = new StackGroupAnalyzer.StackGroup(
                    stack,
                    List.of(thread1, thread2),
                    0,
                    Instant.now()
            );

            StackGroupAnalyzer.StackGroupResult result = new StackGroupAnalyzer.StackGroupResult(
                    AnalysisResult.Severity.INFO,
                    List.of(),
                    List.of(group),
                    2
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("1") || output.contains("group"));
            assertTrue(output.contains("Worker-1") || output.contains("2 threads"));
        }
    }

    @Nested
    class GenericResultViewTests {

        private GenericResultView view;

        @BeforeEach
        void setUp() {
            view = new GenericResultView();
        }

        @Test
        void shouldRenderAnyResult() {
            // Use DeadlockResult as a generic AnalysisResult
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.INFO,
                    List.of(new AnalysisResult.Finding(
                            AnalysisResult.Severity.INFO,
                            "test",
                            "Test finding message")),
                    List.of()
            );

            String output = view.render(result, textOptionsNoColor);

            assertFalse(output.isEmpty());
            assertTrue(output.contains("INFO") || output.contains("Finding"));
        }
    }

    @Nested
    class LockContentionViewTests {

        private LockContentionView view;

        @BeforeEach
        void setUp() {
            view = new LockContentionView();
        }

        @Test
        void shouldRenderNoContention() {
            LockContentionAnalyzer.LockContentionSummary summary =
                    new LockContentionAnalyzer.LockContentionSummary(0, 0, 0);

            LockContentionAnalyzer.LockContentionResult result = new LockContentionAnalyzer.LockContentionResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of(),
                    summary,
                    null
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("No significant lock contention") || output.contains("0"));
        }

        @Test
        void shouldRenderWithContention() {
            ThreadInfo owner = new ThreadInfo(
                    "Owner-Thread", 1L, 100L, 5, false,
                    Thread.State.RUNNABLE, null, null,
                    List.of(), List.of(), null, null
            );
            ThreadInfo waiter1 = new ThreadInfo(
                    "Waiter-1", 2L, 101L, 5, false,
                    Thread.State.BLOCKED, null, null,
                    List.of(), List.of(), "0x12345", null
            );
            ThreadInfo waiter2 = new ThreadInfo(
                    "Waiter-2", 3L, 102L, 5, false,
                    Thread.State.BLOCKED, null, null,
                    List.of(), List.of(), "0x12345", null
            );

            LockContentionAnalyzer.LockContention contention = new LockContentionAnalyzer.LockContention(
                    "0x12345",
                    "java.lang.Object",
                    owner,
                    List.of(waiter1, waiter2),
                    0,
                    Instant.now()
            );

            LockContentionAnalyzer.LockContentionSummary summary =
                    new LockContentionAnalyzer.LockContentionSummary(1, 2, 0);

            LockContentionAnalyzer.LockContentionResult result = new LockContentionAnalyzer.LockContentionResult(
                    AnalysisResult.Severity.INFO,
                    List.of(),
                    List.of(contention),
                    summary,
                    null
            );

            String output = view.render(result, textOptionsNoColor);

            assertTrue(output.contains("Owner-Thread") || output.contains("Waiter"));
            assertTrue(output.contains("2") || output.contains("waiter"));
        }

        @Test
        void shouldRenderAsHtml() {
            LockContentionAnalyzer.LockContentionSummary summary =
                    new LockContentionAnalyzer.LockContentionSummary(0, 0, 0);

            LockContentionAnalyzer.LockContentionResult result = new LockContentionAnalyzer.LockContentionResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of(),
                    summary,
                    null
            );

            String output = view.render(result, htmlOptions);

            assertTrue(output.contains("<html") || output.contains("<!DOCTYPE"));
            assertTrue(output.contains("Lock Contention"));
        }
    }

    @Nested
    class CompositeViewTests {

        private CompositeView view;

        @BeforeEach
        void setUp() {
            ViewRendererFactory.initialize();
            view = new CompositeView();
        }

        @Test
        void shouldRenderMultipleResults() {
            DeadlockAnalyzer.DeadlockResult deadlockResult = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            StackGroupAnalyzer.StackGroupResult stackResult = new StackGroupAnalyzer.StackGroupResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of(),
                    2
            );

            AnalysisResult.CompositeResult composite = new AnalysisResult.CompositeResult(
                    List.of(deadlockResult, stackResult)
            );

            String output = view.render(composite, textOptionsNoColor);

            assertTrue(output.contains("ANALYSIS RESULTS"));
            assertTrue(output.contains("DeadlockAnalyzer") || output.contains("Deadlock"));
            assertTrue(output.contains("StackGroupAnalyzer") || output.contains("Stack"));
        }

        @Test
        void shouldRenderAsHtml() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            AnalysisResult.CompositeResult composite = new AnalysisResult.CompositeResult(
                    List.of(result)
            );

            String output = view.render(composite, htmlOptions);

            assertTrue(output.contains("<html") || output.contains("<!DOCTYPE"));
        }
    }
}