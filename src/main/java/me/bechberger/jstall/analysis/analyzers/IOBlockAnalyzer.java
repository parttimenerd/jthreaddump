package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

/**
 * Analyzer that detects threads blocked on I/O operations.
 * Works with both thread dump stack traces and JFR I/O events.
 */
public class IOBlockAnalyzer implements Analyzer<IOBlockAnalyzer.IOBlockResult> {

    private static final String NAME = "IOBlockAnalyzer";

    // I/O blocking patterns in stack traces
    private static final Map<String, IOType> IO_PATTERNS = Map.ofEntries(
            // Socket I/O
            Map.entry("java.net.SocketInputStream.read", IOType.SOCKET_READ),
            Map.entry("java.net.SocketInputStream.socketRead", IOType.SOCKET_READ),
            Map.entry("java.net.SocketOutputStream.write", IOType.SOCKET_WRITE),
            Map.entry("java.net.Socket.connect", IOType.SOCKET_CONNECT),
            Map.entry("java.net.PlainSocketImpl.accept", IOType.SOCKET_ACCEPT),
            Map.entry("java.net.ServerSocket.accept", IOType.SOCKET_ACCEPT),
            Map.entry("sun.nio.ch.SocketChannelImpl.read", IOType.SOCKET_READ),
            Map.entry("sun.nio.ch.SocketChannelImpl.write", IOType.SOCKET_WRITE),
            Map.entry("sun.nio.ch.ServerSocketChannelImpl.accept", IOType.SOCKET_ACCEPT),

            // File I/O
            Map.entry("java.io.FileInputStream.read", IOType.FILE_READ),
            Map.entry("java.io.FileInputStream.readBytes", IOType.FILE_READ),
            Map.entry("java.io.FileOutputStream.write", IOType.FILE_WRITE),
            Map.entry("java.io.FileOutputStream.writeBytes", IOType.FILE_WRITE),
            Map.entry("java.io.RandomAccessFile.read", IOType.FILE_READ),
            Map.entry("java.io.RandomAccessFile.write", IOType.FILE_WRITE),
            Map.entry("sun.nio.ch.FileChannelImpl.read", IOType.FILE_READ),
            Map.entry("sun.nio.ch.FileChannelImpl.write", IOType.FILE_WRITE),

            // NIO selectors
            Map.entry("sun.nio.ch.EPollSelectorImpl.doSelect", IOType.SELECTOR),
            Map.entry("sun.nio.ch.KQueueSelectorImpl.doSelect", IOType.SELECTOR),
            Map.entry("sun.nio.ch.WindowsSelectorImpl.doSelect", IOType.SELECTOR),
            Map.entry("java.nio.channels.Selector.select", IOType.SELECTOR),

            // Process I/O
            Map.entry("java.lang.ProcessImpl.waitFor", IOType.PROCESS_WAIT),
            Map.entry("java.lang.Process$PipeInputStream.read", IOType.PROCESS_READ),

            // Database/JDBC
            Map.entry("com.mysql.cj.protocol", IOType.DATABASE),
            Map.entry("oracle.jdbc.driver", IOType.DATABASE),
            Map.entry("org.postgresql.core", IOType.DATABASE),
            Map.entry("com.microsoft.sqlserver.jdbc", IOType.DATABASE),

            // HTTP clients
            Map.entry("java.net.http.HttpClient", IOType.HTTP),
            Map.entry("org.apache.http", IOType.HTTP),
            Map.entry("okhttp3", IOType.HTTP)
    );

    public enum IOType {
        SOCKET_READ("Socket Read"),
        SOCKET_WRITE("Socket Write"),
        SOCKET_CONNECT("Socket Connect"),
        SOCKET_ACCEPT("Socket Accept"),
        FILE_READ("File Read"),
        FILE_WRITE("File Write"),
        SELECTOR("NIO Selector"),
        PROCESS_WAIT("Process Wait"),
        PROCESS_READ("Process Read"),
        DATABASE("Database"),
        HTTP("HTTP"),
        UNKNOWN("Unknown I/O");

        private final String displayName;

        IOType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Detects threads blocked on I/O operations (socket, file, database)";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 55;
    }

    @Override
    public @NotNull IOBlockResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;
        List<IOBlockedThread> blockedThreads = new ArrayList<>();
        Map<IOType, Integer> countsByType = new EnumMap<>(IOType.class);

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);
            List<ThreadInfo> filtered = context.getFilteredThreads(dump);

            for (ThreadInfo thread : filtered) {
                IOType ioType = detectIOBlocking(thread);
                if (ioType != null) {
                    String blockingFrame = findBlockingFrame(thread);
                    blockedThreads.add(new IOBlockedThread(
                            thread,
                            ioType,
                            blockingFrame,
                            dumpIndex,
                            dump.timestamp()
                    ));
                    countsByType.merge(ioType, 1, Integer::sum);
                }
            }
        }

        // Group by I/O type and generate findings
        Map<IOType, List<IOBlockedThread>> byType = new LinkedHashMap<>();
        for (IOBlockedThread bt : blockedThreads) {
            byType.computeIfAbsent(bt.ioType(), k -> new ArrayList<>()).add(bt);
        }

        for (Map.Entry<IOType, List<IOBlockedThread>> entry : byType.entrySet()) {
            IOType type = entry.getKey();
            List<IOBlockedThread> threads = entry.getValue();

            if (threads.size() >= 5) {
                AnalysisResult.Severity severity = threads.size() >= 10
                        ? AnalysisResult.Severity.WARNING
                        : AnalysisResult.Severity.INFO;

                if (severity.isWorseThan(worstSeverity)) {
                    worstSeverity = severity;
                }

                List<ThreadInfo> affected = threads.stream()
                        .map(IOBlockedThread::thread)
                        .toList();

                findings.add(AnalysisResult.Finding.builder(severity, "io-blocked",
                        String.format("%d threads blocked on %s", threads.size(), type.getDisplayName()))
                        .affectedThreads(affected)
                        .detail("ioType", type.name())
                        .detail("count", threads.size())
                        .build());
            }
        }

        // Check for threads stuck in I/O across multiple dumps
        if (context.isMultiDump()) {
            Map<String, List<IOBlockedThread>> byThreadName = new LinkedHashMap<>();
            for (IOBlockedThread bt : blockedThreads) {
                if (bt.thread().name() != null) {
                    byThreadName.computeIfAbsent(bt.thread().name(), k -> new ArrayList<>()).add(bt);
                }
            }

            for (Map.Entry<String, List<IOBlockedThread>> entry : byThreadName.entrySet()) {
                if (entry.getValue().size() >= context.getDumpCount() * 0.8) {
                    // Thread stuck in I/O across most dumps
                    AnalysisResult.Severity severity = AnalysisResult.Severity.WARNING;
                    if (severity.isWorseThan(worstSeverity)) {
                        worstSeverity = severity;
                    }

                    IOBlockedThread first = entry.getValue().getFirst();
                    findings.add(AnalysisResult.Finding.builder(severity, "io-stuck",
                            String.format("Thread '%s' stuck in %s across %d dumps",
                                    entry.getKey(), first.ioType().getDisplayName(), entry.getValue().size()))
                            .affectedThread(first.thread())
                            .detail("threadName", entry.getKey())
                            .detail("ioType", first.ioType().name())
                            .detail("dumpCount", entry.getValue().size())
                            .build());
                }
            }
        }

        IOBlockSummary summary = new IOBlockSummary(
                blockedThreads.size(),
                countsByType,
                findMostCommonType(countsByType)
        );

        // Analyze JFR I/O events if available
        JfrIOAnalysis jfrAnalysis = null;
        if (context.hasJfr()) {
            jfrAnalysis = analyzeJfrIO(context, findings);
            if (jfrAnalysis != null) {
                for (AnalysisResult.Finding finding : jfrAnalysis.additionalFindings()) {
                    if (finding.severity().isWorseThan(worstSeverity)) {
                        worstSeverity = finding.severity();
                    }
                }
            }
        }

        return new IOBlockResult(worstSeverity, findings, blockedThreads, summary, jfrAnalysis);
    }

    /**
     * Analyze JFR I/O events to complement thread dump analysis
     */
    private JfrIOAnalysis analyzeJfrIO(AnalysisContext context, List<AnalysisResult.Finding> findings) {
        JfrParser.JfrData jfrData = context.getJfrDataForDumpRange();
        if (jfrData == null || jfrData.ioEvents().isEmpty()) {
            return null;
        }

        JfrAnalyzer analyzer = new JfrAnalyzer();
        JfrAnalyzer.IOSummary ioSummary = analyzer.getIOSummary(jfrData);
        List<JfrAnalyzer.IOTargetProfile> ioHotspots = analyzer.getIOHotspots(jfrData, 10);
        List<JfrAnalyzer.ThreadIOProfile> threadIOProfiles = analyzer.getIOByThread(jfrData, 10);

        // Find slow I/O operations (> 100ms)
        List<JfrParser.IOEvent> slowOps = analyzer.getSlowIOOperations(jfrData, Duration.ofMillis(100));

        // Generate findings for slow I/O
        if (!slowOps.isEmpty()) {
            JfrParser.IOEvent slowest = slowOps.getFirst();
            findings.add(AnalysisResult.Finding.builder(
                    AnalysisResult.Severity.INFO, "jfr-slow-io",
                    String.format("%d slow I/O operations detected (slowest: %dms on %s)",
                            slowOps.size(), slowest.getDurationMs(), slowest.getTarget()))
                    .detail("slowOpCount", slowOps.size())
                    .detail("slowestDurationMs", slowest.getDurationMs())
                    .detail("slowestTarget", slowest.getTarget())
                    .build());
        }

        // Report high I/O time
        if (ioSummary.totalDuration().toMillis() > 5000) {
            findings.add(AnalysisResult.Finding.builder(
                    AnalysisResult.Severity.WARNING, "jfr-high-io-time",
                    String.format("High total I/O time: %dms across %d events",
                            ioSummary.totalDuration().toMillis(), ioSummary.totalEvents()))
                    .detail("totalDurationMs", ioSummary.totalDuration().toMillis())
                    .detail("totalEvents", ioSummary.totalEvents())
                    .detail("totalBytes", ioSummary.totalBytes())
                    .build());
        }

        // Report I/O hotspots
        if (!ioHotspots.isEmpty()) {
            JfrAnalyzer.IOTargetProfile hottest = ioHotspots.getFirst();
            if (hottest.totalDuration().toMillis() > 1000) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "jfr-io-hotspot",
                        String.format("I/O hotspot: %s (%dms, %s)",
                                hottest.target(), hottest.totalDuration().toMillis(), hottest.getFormattedSize()))
                        .detail("target", hottest.target())
                        .detail("durationMs", hottest.totalDuration().toMillis())
                        .detail("bytes", hottest.totalBytes())
                        .build());
            }
        }

        return new JfrIOAnalysis(ioSummary, ioHotspots, threadIOProfiles, slowOps, List.of());
    }

    private IOType detectIOBlocking(ThreadInfo thread) {
        if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) {
            return null;
        }

        // Check if thread state indicates blocking
        if (thread.state() != Thread.State.RUNNABLE &&
            thread.state() != Thread.State.TIMED_WAITING &&
            thread.state() != Thread.State.WAITING) {
            // For non-runnable states, still check if blocked on I/O
        }

        for (StackFrame frame : thread.stackTrace()) {
            String fullMethod = frame.className() + "." + frame.methodName();

            // Check exact matches
            for (Map.Entry<String, IOType> entry : IO_PATTERNS.entrySet()) {
                if (fullMethod.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    private String findBlockingFrame(ThreadInfo thread) {
        if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) {
            return null;
        }

        for (StackFrame frame : thread.stackTrace()) {
            String fullMethod = frame.className() + "." + frame.methodName();
            for (String pattern : IO_PATTERNS.keySet()) {
                if (fullMethod.contains(pattern)) {
                    return frame.toString();
                }
            }
        }

        return thread.stackTrace().getFirst().toString();
    }

    private IOType findMostCommonType(Map<IOType, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public record IOBlockedThread(
            ThreadInfo thread,
            IOType ioType,
            String blockingFrame,
            int dumpIndex,
            java.time.Instant timestamp
    ) {}

    public record IOBlockSummary(
            int totalBlocked,
            Map<IOType, Integer> countsByType,
            IOType mostCommonType
    ) {}

    /**
     * JFR-based I/O analysis results
     */
    public record JfrIOAnalysis(
            JfrAnalyzer.IOSummary summary,
            List<JfrAnalyzer.IOTargetProfile> hotspots,
            List<JfrAnalyzer.ThreadIOProfile> threadProfiles,
            List<JfrParser.IOEvent> slowOperations,
            List<AnalysisResult.Finding> additionalFindings
    ) {
        public JfrIOAnalysis {
            hotspots = hotspots != null ? List.copyOf(hotspots) : List.of();
            threadProfiles = threadProfiles != null ? List.copyOf(threadProfiles) : List.of();
            slowOperations = slowOperations != null ? List.copyOf(slowOperations) : List.of();
            additionalFindings = additionalFindings != null ? List.copyOf(additionalFindings) : List.of();
        }
    }

    public static class IOBlockResult extends AnalysisResult {
        private final List<IOBlockedThread> blockedThreads;
        private final IOBlockSummary summary;
        private final JfrIOAnalysis jfrAnalysis;

        public IOBlockResult(Severity severity, List<Finding> findings,
                            List<IOBlockedThread> blockedThreads, IOBlockSummary summary,
                            JfrIOAnalysis jfrAnalysis) {
            super(NAME, severity, findings);
            this.blockedThreads = List.copyOf(blockedThreads);
            this.summary = summary;
            this.jfrAnalysis = jfrAnalysis;
        }

        public List<IOBlockedThread> getBlockedThreads() {
            return blockedThreads;
        }

        public IOBlockSummary getIOSummary() {
            return summary;
        }

        public JfrIOAnalysis getJfrAnalysis() {
            return jfrAnalysis;
        }

        public boolean hasJfrData() {
            return jfrAnalysis != null;
        }

        public List<IOBlockedThread> getByType(IOType type) {
            return blockedThreads.stream()
                    .filter(bt -> bt.ioType() == type)
                    .toList();
        }

        @Override
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (summary.totalBlocked() == 0) {
                sb.append("No I/O blocked threads in dumps");
            } else {
                String mostCommon = summary.mostCommonType() != null
                        ? summary.mostCommonType().getDisplayName()
                        : "various";
                sb.append(String.format("%d threads blocked on I/O (mostly %s)",
                        summary.totalBlocked(), mostCommon));
            }
            if (jfrAnalysis != null && jfrAnalysis.summary() != null) {
                sb.append(String.format("; JFR: %d I/O events, %s",
                        jfrAnalysis.summary().totalEvents(),
                        jfrAnalysis.summary().getFormattedSize()));
            }
            return sb.toString();
        }
    }
}