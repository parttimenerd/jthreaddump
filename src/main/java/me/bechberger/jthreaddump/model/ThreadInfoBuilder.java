package me.bechberger.jthreaddump.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent builder for {@link ThreadInfo} to simplify construction in tests and parsing code.
 */
public final class ThreadInfoBuilder {
    private String name;
    private Long threadId;
    private Long nativeId;
    private Integer priority;
    private Boolean daemon;
    private Thread.State state;
    private Double cpuTimeSec;
    private Double elapsedTimeSec;
    private final List<StackFrame> stackTrace = new ArrayList<>();
    private final List<LockInfo> locks = new ArrayList<>();
    private String additionalInfo;
    private Long carryingVirtualThreadId;

    private ThreadInfoBuilder() {}

    public static ThreadInfoBuilder create() {
        return new ThreadInfoBuilder();
    }

    public static ThreadInfoBuilder from(ThreadInfo other) {
        ThreadInfoBuilder b = new ThreadInfoBuilder();
        if (other == null) return b;
        b.name = other.name();
        b.threadId = other.threadId();
        b.nativeId = other.nativeId();
        b.priority = other.priority();
        b.daemon = other.daemon();
        b.state = other.state();
        b.cpuTimeSec = other.cpuTimeSec();
        b.elapsedTimeSec = other.elapsedTimeSec();
        if (other.stackTrace() != null) b.stackTrace.addAll(other.stackTrace());
        if (other.locks() != null) b.locks.addAll(other.locks());
        b.additionalInfo = other.additionalInfo();
        b.carryingVirtualThreadId = other.carryingVirtualThreadId();
        return b;
    }

    public ThreadInfoBuilder name(String name) { this.name = name; return this; }
    public ThreadInfoBuilder threadId(Long threadId) { this.threadId = threadId; return this; }
    public ThreadInfoBuilder nativeId(Long nativeId) { this.nativeId = nativeId; return this; }
    public ThreadInfoBuilder priority(Integer priority) { this.priority = priority; return this; }
    public ThreadInfoBuilder daemon(Boolean daemon) { this.daemon = daemon; return this; }
    public ThreadInfoBuilder state(Thread.State state) { this.state = state; return this; }
    public ThreadInfoBuilder cpuTimeSec(Double cpuTimeSec) { this.cpuTimeSec = cpuTimeSec; return this; }
    public ThreadInfoBuilder elapsedTimeSec(Double elapsedTimeSec) { this.elapsedTimeSec = elapsedTimeSec; return this; }

    public ThreadInfoBuilder stackTrace(List<StackFrame> frames) {
        this.stackTrace.clear();
        if (frames != null) this.stackTrace.addAll(frames);
        return this;
    }

    public ThreadInfoBuilder stackTrace(StackFrame... frames) {
        this.stackTrace.clear();
        for (StackFrame frame : frames) {
            if (frame != null) this.stackTrace.add(frame);
        }
        return this;
    }

    public ThreadInfoBuilder addStackFrame(StackFrame frame) {
        if (frame != null) this.stackTrace.add(frame);
        return this;
    }

    public ThreadInfoBuilder locks(List<LockInfo> locks) {
        this.locks.clear();
        if (locks != null) this.locks.addAll(locks);
        return this;
    }

    public ThreadInfoBuilder locks(LockInfo... locks) {
        this.locks.clear();
        for (LockInfo lock : locks) {
            if (lock != null) this.locks.add(lock);
        }
        return this;
    }

    public ThreadInfoBuilder addLock(LockInfo lock) {
        if (lock != null) this.locks.add(lock);
        return this;
    }

    public ThreadInfoBuilder additionalInfo(String additionalInfo) { this.additionalInfo = additionalInfo; return this; }
    public ThreadInfoBuilder carryingVirtualThreadId(Long carryingVirtualThreadId) { this.carryingVirtualThreadId = carryingVirtualThreadId; return this; }

    public ThreadInfo build() {
        // Defensive copies; ThreadInfo constructor will also copy but keep builder safe
        List<StackFrame> frames = this.stackTrace.isEmpty() ? List.of() : List.copyOf(this.stackTrace);
        List<LockInfo> locks = this.locks.isEmpty() ? List.of() : List.copyOf(this.locks);
        return new ThreadInfo(
                name,
                threadId,
                nativeId,
                priority,
                daemon,
                state,
                cpuTimeSec,
                elapsedTimeSec,
                frames,
                locks,
                additionalInfo,
                carryingVirtualThreadId
        );
    }
}