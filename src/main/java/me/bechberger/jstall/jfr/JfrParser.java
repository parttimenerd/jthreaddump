package me.bechberger.jstall.jfr;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Parser for JFR (Java Flight Recorder) files.
 * Extracts profiling data, lock contention events, and allocation information.
 */
public class JfrParser {

    public static final List<String> events = List.of(
            "jdk.ExecutionSample",
            "jdk.NativeMethodSample",
            "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait",
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ClassLoad",
            "jdk.FileRead",
            "jdk.FileWrite",
            "jdk.SocketRead",
            "jdk.SocketWrite"
    );

    /**
     * Parse a JFR file and extract relevant events
     */
    public JfrData parse(@NotNull Path jfrFile) throws IOException {
        return parse(jfrFile, null, null);
    }

    /**
     * Parse a JFR file within a time range
     */
    public JfrData parse(@NotNull Path jfrFile, @Nullable Instant startTime, @Nullable Instant endTime)
            throws IOException {
        List<MethodSample> executionSamples = new ArrayList<>();
        List<LockEvent> lockEvents = new ArrayList<>();
        List<AllocationEvent> allocationEvents = new ArrayList<>();
        List<ClassLoadEvent> classLoadEvents = new ArrayList<>();
        List<IOEvent> ioEvents = new ArrayList<>();

        try (RecordingFile recording = new RecordingFile(jfrFile)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                Instant eventTime = event.getStartTime();

                // Filter by time range if specified
                if (startTime != null && eventTime.isBefore(startTime)) continue;
                if (endTime != null && eventTime.isAfter(endTime)) continue;

                String eventType = event.getEventType().getName();

                switch (eventType) {
                    case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> {
                        MethodSample sample = parseExecutionSample(event);
                        if (sample != null) {
                            executionSamples.add(sample);
                        }
                    }
                    case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait" -> {
                        LockEvent lockEvent = parseLockEvent(event);
                        if (lockEvent != null) {
                            lockEvents.add(lockEvent);
                        }
                    }
                    case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" -> {
                        AllocationEvent alloc = parseAllocationEvent(event);
                        if (alloc != null) {
                            allocationEvents.add(alloc);
                        }
                    }
                    case "jdk.ClassLoad" -> {
                        ClassLoadEvent classLoad = parseClassLoadEvent(event);
                        if (classLoad != null) {
                            classLoadEvents.add(classLoad);
                        }
                    }
                    case "jdk.FileRead", "jdk.FileWrite" -> {
                        IOEvent ioEvent = parseFileIOEvent(event);
                        if (ioEvent != null) {
                            ioEvents.add(ioEvent);
                        }
                    }
                    case "jdk.SocketRead", "jdk.SocketWrite" -> {
                        IOEvent ioEvent = parseSocketIOEvent(event);
                        if (ioEvent != null) {
                            ioEvents.add(ioEvent);
                        }
                    }
                }
            }
        }

        return new JfrData(
                jfrFile,
                executionSamples,
                lockEvents,
                allocationEvents,
                classLoadEvents,
                ioEvents
        );
    }

    private @Nullable MethodSample parseExecutionSample(RecordedEvent event) {
        try {
            RecordedStackTrace stackTrace = event.getStackTrace();
            if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
                return null;
            }

            String threadName = null;
            Long threadId = null;
            if (event.hasField("sampledThread")) {
                var thread = event.getThread("sampledThread");
                if (thread != null) {
                    threadName = thread.getJavaName();
                    threadId = thread.getJavaThreadId();
                }
            }

            List<StackFrameInfo> frames = new ArrayList<>();
            for (RecordedFrame frame : stackTrace.getFrames()) {
                RecordedMethod method = frame.getMethod();
                if (method != null) {
                    frames.add(new StackFrameInfo(
                            method.getType().getName(),
                            method.getName(),
                            frame.getLineNumber(),
                            frame.isJavaFrame()
                    ));
                }
            }

            String state = event.hasField("state") ? event.getString("state") : null;

            return new MethodSample(
                    event.getStartTime(),
                    threadName,
                    threadId,
                    state,
                    frames
            );
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable LockEvent parseLockEvent(RecordedEvent event) {
        try {
            String threadName = null;
            Long threadId = null;
            if (event.hasField("eventThread")) {
                var thread = event.getThread("eventThread");
                if (thread != null) {
                    threadName = thread.getJavaName();
                    threadId = thread.getJavaThreadId();
                }
            }

            String monitorClass = null;
            if (event.hasField("monitorClass")) {
                var clazz = event.getClass("monitorClass");
                if (clazz != null) {
                    monitorClass = clazz.getName();
                }
            }

            Duration duration = event.getDuration();

            String previousOwner = null;
            if (event.hasField("previousOwner")) {
                var owner = event.getThread("previousOwner");
                if (owner != null) {
                    previousOwner = owner.getJavaName();
                }
            }

            LockEventType type = event.getEventType().getName().contains("Wait")
                    ? LockEventType.WAIT
                    : LockEventType.ENTER;

            return new LockEvent(
                    event.getStartTime(),
                    threadName,
                    threadId,
                    monitorClass,
                    duration,
                    previousOwner,
                    type
            );
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable AllocationEvent parseAllocationEvent(RecordedEvent event) {
        try {
            String threadName = null;
            Long threadId = null;
            if (event.hasField("eventThread")) {
                var thread = event.getThread("eventThread");
                if (thread != null) {
                    threadName = thread.getJavaName();
                    threadId = thread.getJavaThreadId();
                }
            }

            String objectClass = null;
            if (event.hasField("objectClass")) {
                var clazz = event.getClass("objectClass");
                if (clazz != null) {
                    objectClass = clazz.getName();
                }
            }

            long allocationSize = event.hasField("allocationSize")
                    ? event.getLong("allocationSize") : 0;

            RecordedStackTrace stackTrace = event.getStackTrace();
            List<StackFrameInfo> frames = new ArrayList<>();
            if (stackTrace != null) {
                for (RecordedFrame frame : stackTrace.getFrames()) {
                    RecordedMethod method = frame.getMethod();
                    if (method != null) {
                        frames.add(new StackFrameInfo(
                                method.getType().getName(),
                                method.getName(),
                                frame.getLineNumber(),
                                frame.isJavaFrame()
                        ));
                    }
                }
            }

            boolean inNewTLAB = event.getEventType().getName().contains("InNewTLAB");

            return new AllocationEvent(
                    event.getStartTime(),
                    threadName,
                    threadId,
                    objectClass,
                    allocationSize,
                    inNewTLAB,
                    frames
            );
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable ClassLoadEvent parseClassLoadEvent(RecordedEvent event) {
        try {
            String loadedClass = null;
            if (event.hasField("loadedClass")) {
                var clazz = event.getClass("loadedClass");
                if (clazz != null) {
                    loadedClass = clazz.getName();
                }
            }

            String threadName = null;
            if (event.hasField("eventThread")) {
                var thread = event.getThread("eventThread");
                if (thread != null) {
                    threadName = thread.getJavaName();
                }
            }

            String initiatingClassLoader = null;
            if (event.hasField("initiatingClassLoader")) {
                var loader = event.getValue("initiatingClassLoader");
                if (loader != null) {
                    initiatingClassLoader = loader.toString();
                }
            }

            Duration duration = event.getDuration();

            return new ClassLoadEvent(
                    event.getStartTime(),
                    loadedClass,
                    threadName,
                    initiatingClassLoader,
                    duration
            );
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable IOEvent parseFileIOEvent(RecordedEvent event) {
        try {
            String threadName = null;
            Long threadId = null;
            if (event.hasField("eventThread")) {
                var thread = event.getThread("eventThread");
                if (thread != null) {
                    threadName = thread.getJavaName();
                    threadId = thread.getJavaThreadId();
                }
            }

            String path = event.hasField("path") ? event.getString("path") : null;
            long bytesTransferred = event.hasField("bytesRead")
                    ? event.getLong("bytesRead")
                    : (event.hasField("bytesWritten") ? event.getLong("bytesWritten") : 0);
            Duration duration = event.getDuration();

            IOEventType type = event.getEventType().getName().contains("Write")
                    ? IOEventType.FILE_WRITE
                    : IOEventType.FILE_READ;

            RecordedStackTrace stackTrace = event.getStackTrace();
            List<StackFrameInfo> frames = parseStackTrace(stackTrace);

            return new IOEvent(
                    event.getStartTime(),
                    threadName,
                    threadId,
                    type,
                    path,
                    null, // No host for file I/O
                    null, // No port for file I/O
                    bytesTransferred,
                    duration,
                    frames
            );
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable IOEvent parseSocketIOEvent(RecordedEvent event) {
        try {
            String threadName = null;
            Long threadId = null;
            if (event.hasField("eventThread")) {
                var thread = event.getThread("eventThread");
                if (thread != null) {
                    threadName = thread.getJavaName();
                    threadId = thread.getJavaThreadId();
                }
            }

            String host = event.hasField("host") ? event.getString("host") : null;
            Integer port = event.hasField("port") ? event.getInt("port") : null;
            String address = event.hasField("address") ? event.getString("address") : null;
            if (host == null && address != null) {
                host = address;
            }

            long bytesTransferred = event.hasField("bytesRead")
                    ? event.getLong("bytesRead")
                    : (event.hasField("bytesWritten") ? event.getLong("bytesWritten") : 0);
            Duration duration = event.getDuration();

            IOEventType type = event.getEventType().getName().contains("Write")
                    ? IOEventType.SOCKET_WRITE
                    : IOEventType.SOCKET_READ;

            RecordedStackTrace stackTrace = event.getStackTrace();
            List<StackFrameInfo> frames = parseStackTrace(stackTrace);

            return new IOEvent(
                    event.getStartTime(),
                    threadName,
                    threadId,
                    type,
                    null, // No path for socket I/O
                    host,
                    port,
                    bytesTransferred,
                    duration,
                    frames
            );
        } catch (Exception e) {
            return null;
        }
    }

    private List<StackFrameInfo> parseStackTrace(RecordedStackTrace stackTrace) {
        List<StackFrameInfo> frames = new ArrayList<>();
        if (stackTrace != null) {
            for (RecordedFrame frame : stackTrace.getFrames()) {
                RecordedMethod method = frame.getMethod();
                if (method != null) {
                    frames.add(new StackFrameInfo(
                            method.getType().getName(),
                            method.getName(),
                            frame.getLineNumber(),
                            frame.isJavaFrame()
                    ));
                }
            }
        }
        return frames;
    }

    // Data classes

    public record JfrData(
            Path sourcePath,
            List<MethodSample> executionSamples,
            List<LockEvent> lockEvents,
            List<AllocationEvent> allocationEvents,
            List<ClassLoadEvent> classLoadEvents,
            List<IOEvent> ioEvents
    ) {
        public JfrData {
            executionSamples = List.copyOf(executionSamples);
            lockEvents = List.copyOf(lockEvents);
            allocationEvents = List.copyOf(allocationEvents);
            classLoadEvents = List.copyOf(classLoadEvents);
            ioEvents = List.copyOf(ioEvents);
        }

        public boolean isEmpty() {
            return executionSamples.isEmpty() && lockEvents.isEmpty() &&
                   allocationEvents.isEmpty() && classLoadEvents.isEmpty() &&
                   ioEvents.isEmpty();
        }

        public Instant getStartTime() {
            Instant earliest = null;
            for (MethodSample s : executionSamples) {
                if (earliest == null || s.timestamp().isBefore(earliest)) {
                    earliest = s.timestamp();
                }
            }
            for (LockEvent e : lockEvents) {
                if (earliest == null || e.timestamp().isBefore(earliest)) {
                    earliest = e.timestamp();
                }
            }
            for (IOEvent e : ioEvents) {
                if (earliest == null || e.timestamp().isBefore(earliest)) {
                    earliest = e.timestamp();
                }
            }
            return earliest;
        }

        public Instant getEndTime() {
            Instant latest = null;
            for (MethodSample s : executionSamples) {
                if (latest == null || s.timestamp().isAfter(latest)) {
                    latest = s.timestamp();
                }
            }
            for (LockEvent e : lockEvents) {
                if (latest == null || e.timestamp().isAfter(latest)) {
                    latest = e.timestamp();
                }
            }
            for (IOEvent e : ioEvents) {
                if (latest == null || e.timestamp().isAfter(latest)) {
                    latest = e.timestamp();
                }
            }
            return latest;
        }

        /**
         * Get events within a time window (for correlating with thread dumps)
         */
        public JfrData filterByTimeRange(Instant start, Instant end) {
            return new JfrData(
                    sourcePath,
                    executionSamples.stream()
                            .filter(s -> !s.timestamp().isBefore(start) && !s.timestamp().isAfter(end))
                            .toList(),
                    lockEvents.stream()
                            .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                            .toList(),
                    allocationEvents.stream()
                            .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                            .toList(),
                    classLoadEvents.stream()
                            .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                            .toList(),
                    ioEvents.stream()
                            .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                            .toList()
            );
        }

        /**
         * Get samples for a specific thread
         */
        public List<MethodSample> getSamplesForThread(String threadName) {
            return executionSamples.stream()
                    .filter(s -> threadName.equals(s.threadName()))
                    .toList();
        }

        /**
         * Get I/O events for a specific thread
         */
        public List<IOEvent> getIOEventsForThread(String threadName) {
            return ioEvents.stream()
                    .filter(e -> threadName.equals(e.threadName()))
                    .toList();
        }
    }

    public record MethodSample(
            Instant timestamp,
            String threadName,
            Long threadId,
            String threadState,
            List<StackFrameInfo> stackTrace
    ) {
        public MethodSample {
            stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
        }

        public String getTopMethod() {
            if (stackTrace.isEmpty()) return null;
            StackFrameInfo top = stackTrace.getFirst();
            return top.className() + "." + top.methodName();
        }
    }

    public record StackFrameInfo(
            String className,
            String methodName,
            int lineNumber,
            boolean isJavaFrame
    ) {
        @Override
        public String toString() {
            if (lineNumber > 0) {
                return className + "." + methodName + ":" + lineNumber;
            }
            return className + "." + methodName;
        }
    }

    public enum LockEventType {
        ENTER, WAIT
    }

    public record LockEvent(
            Instant timestamp,
            String threadName,
            Long threadId,
            String monitorClass,
            Duration duration,
            String previousOwner,
            LockEventType type
    ) {}

    public record AllocationEvent(
            Instant timestamp,
            String threadName,
            Long threadId,
            String objectClass,
            long allocationSize,
            boolean inNewTLAB,
            List<StackFrameInfo> stackTrace
    ) {
        public AllocationEvent {
            stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
        }

        public String getAllocationSite() {
            if (stackTrace.isEmpty()) return null;
            StackFrameInfo top = stackTrace.getFirst();
            return top.className() + "." + top.methodName();
        }
    }

    public record ClassLoadEvent(
            Instant timestamp,
            String loadedClass,
            String threadName,
            String classLoader,
            Duration duration
    ) {}

    public enum IOEventType {
        FILE_READ("File Read"),
        FILE_WRITE("File Write"),
        SOCKET_READ("Socket Read"),
        SOCKET_WRITE("Socket Write");

        private final String displayName;

        IOEventType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isRead() {
            return this == FILE_READ || this == SOCKET_READ;
        }

        public boolean isWrite() {
            return this == FILE_WRITE || this == SOCKET_WRITE;
        }

        public boolean isFile() {
            return this == FILE_READ || this == FILE_WRITE;
        }

        public boolean isSocket() {
            return this == SOCKET_READ || this == SOCKET_WRITE;
        }
    }

    public record IOEvent(
            Instant timestamp,
            String threadName,
            Long threadId,
            IOEventType type,
            String path,         // For file I/O
            String host,         // For socket I/O
            Integer port,        // For socket I/O
            long bytesTransferred,
            Duration duration,
            List<StackFrameInfo> stackTrace
    ) {
        public IOEvent {
            stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
        }

        public String getTarget() {
            if (path != null) {
                return path;
            }
            if (host != null) {
                return port != null ? host + ":" + port : host;
            }
            return "unknown";
        }

        public String getIOSite() {
            if (stackTrace.isEmpty()) return null;
            StackFrameInfo top = stackTrace.getFirst();
            return top.className() + "." + top.methodName();
        }

        public long getDurationMs() {
            return duration != null ? duration.toMillis() : 0;
        }
    }
}