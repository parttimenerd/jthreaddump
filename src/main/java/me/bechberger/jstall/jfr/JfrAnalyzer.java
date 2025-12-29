package me.bechberger.jstall.jfr;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

/**
 * Analyzes JFR data to extract profiling insights.
 */
public class JfrAnalyzer {

    /**
     * Get the top methods by sample count (hottest methods)
     */
    public List<MethodProfile> getHottestMethods(@NotNull JfrParser.JfrData data, int limit) {
        Map<String, Long> methodCounts = new HashMap<>();

        for (JfrParser.MethodSample sample : data.executionSamples()) {
            if (!sample.stackTrace().isEmpty()) {
                String topMethod = sample.getTopMethod();
                if (topMethod != null) {
                    methodCounts.merge(topMethod, 1L, Long::sum);
                }
            }
        }

        long totalSamples = data.executionSamples().size();

        return methodCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MethodProfile(
                        e.getKey(),
                        e.getValue(),
                        totalSamples > 0 ? (e.getValue() * 100.0) / totalSamples : 0
                ))
                .toList();
    }

    /**
     * Get method profiles aggregated by stack trace (flame graph data)
     */
    public List<StackProfile> getStackProfiles(@NotNull JfrParser.JfrData data, int maxDepth) {
        Map<List<String>, Long> stackCounts = new HashMap<>();

        for (JfrParser.MethodSample sample : data.executionSamples()) {
            List<String> stack = sample.stackTrace().stream()
                    .limit(maxDepth)
                    .map(f -> f.className() + "." + f.methodName())
                    .toList();

            if (!stack.isEmpty()) {
                stackCounts.merge(stack, 1L, Long::sum);
            }
        }

        long totalSamples = data.executionSamples().size();

        return stackCounts.entrySet().stream()
                .sorted(Map.Entry.<List<String>, Long>comparingByValue().reversed())
                .limit(100)
                .map(e -> new StackProfile(
                        e.getKey(),
                        e.getValue(),
                        totalSamples > 0 ? (e.getValue() * 100.0) / totalSamples : 0
                ))
                .toList();
    }

    /**
     * Get hottest threads by sample count
     */
    public List<ThreadProfile> getHottestThreads(@NotNull JfrParser.JfrData data, int limit) {
        Map<String, Long> threadCounts = new HashMap<>();

        for (JfrParser.MethodSample sample : data.executionSamples()) {
            String threadName = sample.threadName();
            if (threadName != null) {
                threadCounts.merge(threadName, 1L, Long::sum);
            }
        }

        long totalSamples = data.executionSamples().size();

        return threadCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new ThreadProfile(
                        e.getKey(),
                        e.getValue(),
                        totalSamples > 0 ? (e.getValue() * 100.0) / totalSamples : 0
                ))
                .toList();
    }

    /**
     * Get lock contention summary
     */
    public LockContentionSummary getLockContentionSummary(@NotNull JfrParser.JfrData data) {
        Map<String, LockStats> lockStats = new HashMap<>();

        for (JfrParser.LockEvent event : data.lockEvents()) {
            String monitorClass = event.monitorClass() != null ? event.monitorClass() : "unknown";

            lockStats.computeIfAbsent(monitorClass, k -> new LockStats())
                    .addEvent(event);
        }

        List<LockProfile> profiles = lockStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        b.getValue().totalDuration.toMillis(),
                        a.getValue().totalDuration.toMillis()))
                .map(e -> new LockProfile(
                        e.getKey(),
                        e.getValue().count,
                        e.getValue().totalDuration,
                        e.getValue().maxDuration,
                        e.getValue().waiters
                ))
                .toList();

        Duration totalBlockedTime = Duration.ZERO;
        for (LockStats stats : lockStats.values()) {
            totalBlockedTime = totalBlockedTime.plus(stats.totalDuration);
        }

        return new LockContentionSummary(
                data.lockEvents().size(),
                lockStats.size(),
                totalBlockedTime,
                profiles
        );
    }

    /**
     * Get allocation hotspots
     */
    public List<AllocationProfile> getAllocationHotspots(@NotNull JfrParser.JfrData data, int limit) {
        Map<String, AllocationStats> siteStats = new HashMap<>();

        for (JfrParser.AllocationEvent event : data.allocationEvents()) {
            String site = event.getAllocationSite();
            if (site == null) site = "unknown";

            siteStats.computeIfAbsent(site, k -> new AllocationStats())
                    .addEvent(event);
        }

        long totalAllocated = siteStats.values().stream()
                .mapToLong(s -> s.totalBytes)
                .sum();

        return siteStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalBytes, a.getValue().totalBytes))
                .limit(limit)
                .map(e -> new AllocationProfile(
                        e.getKey(),
                        e.getValue().count,
                        e.getValue().totalBytes,
                        totalAllocated > 0 ? (e.getValue().totalBytes * 100.0) / totalAllocated : 0,
                        e.getValue().topClasses
                ))
                .toList();
    }

    /**
     * Get allocation by thread
     */
    public List<ThreadAllocationProfile> getAllocationByThread(@NotNull JfrParser.JfrData data, int limit) {
        Map<String, Long> threadAllocations = new HashMap<>();

        for (JfrParser.AllocationEvent event : data.allocationEvents()) {
            String threadName = event.threadName();
            if (threadName != null) {
                threadAllocations.merge(threadName, event.allocationSize(), Long::sum);
            }
        }

        long totalAllocated = threadAllocations.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        return threadAllocations.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new ThreadAllocationProfile(
                        e.getKey(),
                        e.getValue(),
                        totalAllocated > 0 ? (e.getValue() * 100.0) / totalAllocated : 0
                ))
                .toList();
    }

    /**
     * Get class loading summary
     */
    public ClassLoadingSummary getClassLoadingSummary(@NotNull JfrParser.JfrData data) {
        Map<String, Integer> loaderCounts = new HashMap<>();
        Duration totalDuration = Duration.ZERO;

        for (JfrParser.ClassLoadEvent event : data.classLoadEvents()) {
            String loader = event.classLoader() != null ? event.classLoader() : "bootstrap";
            loaderCounts.merge(loader, 1, Integer::sum);
            if (event.duration() != null) {
                totalDuration = totalDuration.plus(event.duration());
            }
        }

        List<String> recentClasses = data.classLoadEvents().stream()
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .limit(20)
                .map(JfrParser.ClassLoadEvent::loadedClass)
                .filter(Objects::nonNull)
                .toList();

        return new ClassLoadingSummary(
                data.classLoadEvents().size(),
                loaderCounts,
                totalDuration,
                recentClasses
        );
    }

    /**
     * Get I/O activity summary
     */
    public IOSummary getIOSummary(@NotNull JfrParser.JfrData data) {
        Map<JfrParser.IOEventType, IOTypeStats> statsByType = new EnumMap<>(JfrParser.IOEventType.class);

        for (JfrParser.IOEvent event : data.ioEvents()) {
            statsByType.computeIfAbsent(event.type(), k -> new IOTypeStats())
                    .addEvent(event);
        }

        List<IOTypeProfile> profiles = statsByType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalBytes, a.getValue().totalBytes))
                .map(e -> new IOTypeProfile(
                        e.getKey(),
                        e.getValue().count,
                        e.getValue().totalBytes,
                        e.getValue().totalDuration,
                        e.getValue().maxDuration
                ))
                .toList();

        long totalBytes = statsByType.values().stream().mapToLong(s -> s.totalBytes).sum();
        Duration totalDuration = Duration.ZERO;
        for (IOTypeStats stats : statsByType.values()) {
            totalDuration = totalDuration.plus(stats.totalDuration);
        }

        return new IOSummary(
                data.ioEvents().size(),
                totalBytes,
                totalDuration,
                profiles
        );
    }

    /**
     * Get I/O hotspots by target (file path or host:port)
     */
    public List<IOTargetProfile> getIOHotspots(@NotNull JfrParser.JfrData data, int limit) {
        Map<String, IOTargetStats> targetStats = new HashMap<>();

        for (JfrParser.IOEvent event : data.ioEvents()) {
            String target = event.getTarget();
            targetStats.computeIfAbsent(target, k -> new IOTargetStats())
                    .addEvent(event);
        }

        long totalBytes = targetStats.values().stream().mapToLong(s -> s.totalBytes).sum();

        return targetStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalDuration.toMillis(),
                                               a.getValue().totalDuration.toMillis()))
                .limit(limit)
                .map(e -> new IOTargetProfile(
                        e.getKey(),
                        e.getValue().count,
                        e.getValue().totalBytes,
                        totalBytes > 0 ? (e.getValue().totalBytes * 100.0) / totalBytes : 0,
                        e.getValue().totalDuration,
                        e.getValue().types
                ))
                .toList();
    }

    /**
     * Get I/O activity by thread
     */
    public List<ThreadIOProfile> getIOByThread(@NotNull JfrParser.JfrData data, int limit) {
        Map<String, IOTargetStats> threadStats = new HashMap<>();

        for (JfrParser.IOEvent event : data.ioEvents()) {
            String threadName = event.threadName();
            if (threadName != null) {
                threadStats.computeIfAbsent(threadName, k -> new IOTargetStats())
                        .addEvent(event);
            }
        }

        return threadStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalDuration.toMillis(),
                                               a.getValue().totalDuration.toMillis()))
                .limit(limit)
                .map(e -> new ThreadIOProfile(
                        e.getKey(),
                        e.getValue().count,
                        e.getValue().totalBytes,
                        e.getValue().totalDuration,
                        e.getValue().types
                ))
                .toList();
    }

    /**
     * Get hottest methods for a specific thread
     */
    public List<MethodProfile> getHottestMethodsForThread(@NotNull JfrParser.JfrData data,
                                                          @NotNull String threadName, int limit) {
        Map<String, Long> methodCounts = new HashMap<>();
        long threadSamples = 0;

        for (JfrParser.MethodSample sample : data.executionSamples()) {
            if (threadName.equals(sample.threadName()) && !sample.stackTrace().isEmpty()) {
                String topMethod = sample.getTopMethod();
                if (topMethod != null) {
                    methodCounts.merge(topMethod, 1L, Long::sum);
                    threadSamples++;
                }
            }
        }

        final long total = threadSamples;
        return methodCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MethodProfile(
                        e.getKey(),
                        e.getValue(),
                        total > 0 ? (e.getValue() * 100.0) / total : 0
                ))
                .toList();
    }

    /**
     * Get slow I/O operations (above threshold)
     */
    public List<JfrParser.IOEvent> getSlowIOOperations(@NotNull JfrParser.JfrData data,
                                                        Duration threshold) {
        return data.ioEvents().stream()
                .filter(e -> e.duration() != null && e.duration().compareTo(threshold) > 0)
                .sorted((a, b) -> b.duration().compareTo(a.duration()))
                .toList();
    }

    // Helper classes

    private static class LockStats {
        int count = 0;
        Duration totalDuration = Duration.ZERO;
        Duration maxDuration = Duration.ZERO;
        Set<String> waiters = new HashSet<>();

        void addEvent(JfrParser.LockEvent event) {
            count++;
            if (event.duration() != null) {
                totalDuration = totalDuration.plus(event.duration());
                if (event.duration().compareTo(maxDuration) > 0) {
                    maxDuration = event.duration();
                }
            }
            if (event.threadName() != null) {
                waiters.add(event.threadName());
            }
        }
    }

    private static class AllocationStats {
        int count = 0;
        long totalBytes = 0;
        Map<String, Long> classCounts = new HashMap<>();
        List<String> topClasses = new ArrayList<>();

        void addEvent(JfrParser.AllocationEvent event) {
            count++;
            totalBytes += event.allocationSize();
            if (event.objectClass() != null) {
                classCounts.merge(event.objectClass(), event.allocationSize(), Long::sum);
            }
            updateTopClasses();
        }

        void updateTopClasses() {
            topClasses = classCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }

    private static class IOTypeStats {
        int count = 0;
        long totalBytes = 0;
        Duration totalDuration = Duration.ZERO;
        Duration maxDuration = Duration.ZERO;

        void addEvent(JfrParser.IOEvent event) {
            count++;
            totalBytes += event.bytesTransferred();
            if (event.duration() != null) {
                totalDuration = totalDuration.plus(event.duration());
                if (event.duration().compareTo(maxDuration) > 0) {
                    maxDuration = event.duration();
                }
            }
        }
    }

    private static class IOTargetStats {
        int count = 0;
        long totalBytes = 0;
        Duration totalDuration = Duration.ZERO;
        Set<JfrParser.IOEventType> types = EnumSet.noneOf(JfrParser.IOEventType.class);

        void addEvent(JfrParser.IOEvent event) {
            count++;
            totalBytes += event.bytesTransferred();
            if (event.duration() != null) {
                totalDuration = totalDuration.plus(event.duration());
            }
            types.add(event.type());
        }
    }

    // Result records

    public record MethodProfile(
            String method,
            long sampleCount,
            double percentage
    ) {}

    public record StackProfile(
            List<String> stack,
            long sampleCount,
            double percentage
    ) {}

    public record ThreadProfile(
            String threadName,
            long sampleCount,
            double percentage
    ) {}

    public record LockProfile(
            String monitorClass,
            int eventCount,
            Duration totalDuration,
            Duration maxDuration,
            Set<String> waiters
    ) {}

    public record LockContentionSummary(
            int totalEvents,
            int uniqueLocks,
            Duration totalBlockedTime,
            List<LockProfile> hotLocks
    ) {}

    public record AllocationProfile(
            String allocationSite,
            int count,
            long totalBytes,
            double percentage,
            List<String> topClasses
    ) {
        public String getFormattedSize() {
            if (totalBytes >= 1024 * 1024 * 1024) {
                return String.format("%.2f GB", totalBytes / (1024.0 * 1024 * 1024));
            } else if (totalBytes >= 1024 * 1024) {
                return String.format("%.2f MB", totalBytes / (1024.0 * 1024));
            } else if (totalBytes >= 1024) {
                return String.format("%.2f KB", totalBytes / 1024.0);
            }
            return totalBytes + " B";
        }
    }

    public record ThreadAllocationProfile(
            String threadName,
            long totalBytes,
            double percentage
    ) {}

    public record ClassLoadingSummary(
            int totalClasses,
            Map<String, Integer> byLoader,
            Duration totalDuration,
            List<String> recentClasses
    ) {}

    public record IOTypeProfile(
            JfrParser.IOEventType type,
            int count,
            long totalBytes,
            Duration totalDuration,
            Duration maxDuration
    ) {
        public String getFormattedSize() {
            return formatBytes(totalBytes);
        }
    }

    public record IOSummary(
            int totalEvents,
            long totalBytes,
            Duration totalDuration,
            List<IOTypeProfile> byType
    ) {
        public String getFormattedSize() {
            return formatBytes(totalBytes);
        }

        public long totalFileReads() {
            return byType.stream()
                    .filter(p -> p.type() == JfrParser.IOEventType.FILE_READ)
                    .mapToLong(IOTypeProfile::count)
                    .sum();
        }

        public long totalFileWrites() {
            return byType.stream()
                    .filter(p -> p.type() == JfrParser.IOEventType.FILE_WRITE)
                    .mapToLong(IOTypeProfile::count)
                    .sum();
        }

        public long totalSocketReads() {
            return byType.stream()
                    .filter(p -> p.type() == JfrParser.IOEventType.SOCKET_READ)
                    .mapToLong(IOTypeProfile::count)
                    .sum();
        }

        public long totalSocketWrites() {
            return byType.stream()
                    .filter(p -> p.type() == JfrParser.IOEventType.SOCKET_WRITE)
                    .mapToLong(IOTypeProfile::count)
                    .sum();
        }
    }

    public record IOTargetProfile(
            String target,
            int count,
            long totalBytes,
            double percentage,
            Duration totalDuration,
            Set<JfrParser.IOEventType> types
    ) {
        public String getFormattedSize() {
            return formatBytes(totalBytes);
        }
    }

    public record ThreadIOProfile(
            String threadName,
            int count,
            long totalBytes,
            Duration totalDuration,
            Set<JfrParser.IOEventType> types
    ) {
        public String getFormattedSize() {
            return formatBytes(totalBytes);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}