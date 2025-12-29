# jstall – Simple Thread Dump Analyzer

## 1. Purpose

**Primary use case**
> My Java application is stalled (UI or CLI not updating) and I want to know why.

`jstall` is a **small, robust, CLI-only diagnostic tool** for analyzing Java thread dumps. It focuses on detecting stalls, deadlocks, lack of progress, and lock contention by **correlating multiple thread dumps over time**.

Key principles:
- Human-readable output by default
- Machine-readable output available (JSON/YAML)
- Minimal configuration (CLI flags only)
- Robust heuristics (state + CPU + elapsed + stacks)
- Scales to **1000+ threads** per dump

**New feature additions based on advanced use cases:**
- Identical stack grouping across multiple dumps to quickly detect stuck threads.
- RUNNABLE-no-progress detection to identify threads that appear active but make no real progress.
- Elapsed-based long-held lock detection for highlighting contention issues.
- Clear, explanatory stall verdicts with reasons to build trust.
- Thread churn / growth reporting to detect leaks or executor mismanagement.

---

## 2. Supported Inputs

### 2.1 Thread Dump Sources

Supported formats:
- `jstack <pid>` output
- `jcmd <pid> Thread.print` output

Features supported from both:
- Thread states
- CPU time
- Elapsed time
- Stack traces
- Monitor / lock ownership
- JNI global & weak references

Format detection is automatic.

---

## 3. Core Commands

### 3.1 Parse

```bash
jstall parse dump.txt
```

- Parses a single thread dump
- Outputs structured representation
- Default: human-readable text
- Optional: `--output json|yaml|html`

---

### 3.2 Diff

```bash
jstall diff dump1 dump2 [dump3 ...]
```

Compares multiple dumps and answers:
- Which threads appear/disappear
- Which threads made progress
- Which threads are blocked or stuck
- Which locks are contended or long-held
- Groups threads by identical stack traces to quickly highlight stuck threads
- Reports thread churn / growth between dumps

Options:
- `--only-moving`
- `--only-blocked`
- `-m minimal|large|quiet|short-stall`
- `--fail-on-stall`

---

### 3.3 Stall (Live JVM)

```bash
jstall stall <pid> -i 3 -t 10s
```

- Uses Attach API (same-user JVMs only)
- Captures multiple dumps
- Runs diff + stall analysis
- Returns verdict immediately
- Explains reasons for detected stalls (elapsed time, CPU progress, identical stacks)

Exit codes:
- `0` – OK
- `1` – stall suspected
- `2` – deadlock detected
- `3` – error


Diff and stall commands should support the same options for consistency 
(except those specific to getting the thread dumps).

---

## 4. Output Modes

### 4.1 Default Output

- Human-readable
- Well-formatted
- Optimized for terminal use
- Shows identical stack groups, RUNNABLE-no-progress threads, long-held locks, and thread churn

Example:
```
VERDICT: SUSPECTED_STALL
Confidence: HIGH

Reason:
- 12/12 RUNNABLE threads made no progress over 20s
- 15 threads share identical stack:
    at java.net.SocketInputStream.read
    at com.foo.NetClient.receive
- Lock <0xabc123> held for ≥20s by Worker-3
```

### 4.2 Other Formats

- `--output json`
- `--output yaml`
- `--output html` (optional; includes lock dependency graph and expandable stacks)

---

## 5. Thread Model

### 5.1 Canonical Thread States

Parsed exactly as defined by `java.lang.Thread.State`:

- NEW
- RUNNABLE
- BLOCKED
- WAITING
- TIMED_WAITING
- TERMINATED

State alone is **never sufficient** for diagnostics.

---

## 6. Temporal Data (Critical)

Each thread may include:
- **CPU time** (expected to be present)
- **Elapsed time** (wall-clock since thread start)

If CPU time is missing:
- Warning is emitted
- Stall detection degrades
- Only deadlock and stack-diff heuristics remain

Elapsed time is used to:
- Detect lack of progress
- Detect thread restarts
- Detect long-held locks


for stall command:
optional ability to take ExecutionSamples and Nati
veMethodSamples for the whole time period
to find top methods and common stacks across the time period
for every thread that got cpu time

Also show me the most allocating threads
and the most class loading threads

---

## 7. Thread Identity Across Dumps

Thread matching priority:
1. Native thread id (`nid`)
2. Java thread id
3. Thread name (last resort, warning emitted)

Elapsed time sanity checks:
- Decreasing elapsed → thread restarted
- Identical elapsed → duplicate dump

---

## 8. Derived Progress Classification

### 8.1 ProgressClassification

```java
enum ProgressClassification {
    ACTIVE,
    RUNNABLE_NO_PROGRESS,
    BLOCKED_ON_LOCK,
    WAITING_EXPECTED,
    TIMED_WAITING_EXPECTED,
    STUCK,
    RESTARTED,
    TERMINATED,
    IGNORED
}
```

This classification is computed **between dumps**, and feeds new features such as identical stack grouping and long-held lock detection.

---

## 9. State Interpretation Rules

### 9.1 NEW

- Ignored
- Never contributes to stall detection

### 9.2 RUNNABLE

| Condition | Classification |
|---------|----------------|
| ΔCPU > ε | ACTIVE |
| ΔCPU ≈ 0, stack changed | ACTIVE |
| ΔCPU ≈ 0, stack unchanged | RUNNABLE_NO_PROGRESS |

**Captures threads that appear active but are stalled** (e.g., socket-read loops). Identical stack grouping highlights clusters of these threads.

### 9.3 BLOCKED

| Condition | Classification |
|---------|----------------|
| Waiting on lock | BLOCKED_ON_LOCK |
| Same lock across dumps | STUCK |

---

### 9.4 WAITING

| Condition | Classification |
|---------|----------------|
| Known JVM background thread | WAITING_EXPECTED |
| Application thread, no progress | STUCK |

### 9.5 TIMED_WAITING

| Condition | Classification |
|---------|----------------|
| Scheduler / background | TIMED_WAITING_EXPECTED |
| Repeated unchanged | STUCK |

### 9.6 TERMINATED

- Not considered for stall detection
- Used to detect thread churn/growth

have tables that show the count in every dump and the changes
over time

---

## 10. Stall Detection (Application-Level)

Application is **stalled** if **any** of:
1. Deadlock detected
2. ≥ 90% of non-ignored threads classified as:
    - RUNNABLE_NO_PROGRESS
    - BLOCKED_ON_LOCK
    - STUCK
3. All RUNNABLE threads show no progress

Defaults:
- CPU epsilon: 2ms
- Dumps required: ≥ 2
- Explanations include identical stacks, elapsed time, long-held locks, and thread churn

---

## 11. Lock Dependency Graph

Graph model:
- Nodes: Threads, Locks
- Edges:
    - Thread → Lock (waiting)
    - Lock → Thread (owned)

Capabilities:
- Deadlock detection: already builtin to JDK
- Hot lock detection
- Long-held lock detection using elapsed time
- Optional HTML visualization (see Optional / Advanced features)

## 20 Zoom into specific threads
verbose output showing full stacks of selected thread
and how they evolved over time (ignore common base)
have cmd option to only focus on thread names matching glob

---

## 12. Noise Reduction

Ignored by default:
- Threads starting with `GC`
- Threads starting with `VM`
- Finalizer
- Reference Handler
- but also allow to include or even just focus on these

Unless:
- CPU usage > 20% of total

Custom ignore regex supported.


---

## 13. JNI Resource Analysis

Parsed if present:
```
JNI global refs: 247, weak refs: 3181
JNI global refs memory usage: 3363, weak refs: 70049
```

Heuristic:
- Rising JNI globals across dumps
- Elapsed time advanced
- CPU low

→ possible JNI resource leak

have table with the changes over time, maybe even a rate computed

## 21 Analyze GC Activity
Analyze GC activity based on thread dump data.

## 22 Support Loom threads
Differentiate between platform and virtual threads.

Use examples to find out the information


---

## 15. Implementation Constraints

- Java 21
- CLI-only
- No config files
- Jackson for JSON/YAML
- JSON schema provided
- GraalVM native image builds
- CI and test-heavy repository

Use CI/CD and README similar to 
https://github.com/parttimenerd/jstall

Generate schema

Adapt sync-documentation.py and README

---

## 16. Testing Strategy

- Golden thread dumps (jstack + jcmd)
- Known deadlock examples
- RUNNABLE no-progress cases
- Long-held lock tests
- Thread churn / growth detection
- Large synthetic dumps (1000+ threads)
- Renaissance benchmark dumps (via Python script)

---

## 17. Optional / Advanced (Good but Keep Optional)

### 17.1 Lock Dependency Graph → HTML

- Worth keeping optional
- Useful for deep analysis
- Not needed for quick diagnosis
- Already scoped correctly: CLI default: text, optional: `--output html`

### 17.2 Stack Frequency “Top Methods” Table

- People love this, but it can be noisy
- Limit to top N
- Ignore JVM internals by default

---

## 18. Status

This document represents the **combined, finalized functional and design specification** for `jstall`, including advanced features inspired by real-world thread-dump usage:
- Identical stack grouping across multiple dumps
- RUNNABLE-no-progress detection
- Elapsed-based long-held lock detection
- Clear stall explanations
- Thread churn / growth reporting
- Optional HTML lock dependency graph visualization
- Optional stack frequency top methods table



Support, Feedback, Contributing
-------------------------------
This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall/issues) issues.
Contribution and feedback are encouraged and always welcome.
For more information about how to contribute, the project structure,
as well as additional contribution information, see our Contribution Guidelines.


License
-------
MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors