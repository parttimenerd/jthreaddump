# Java Thread Dump Parser Library

[![CI](https://github.com/parttimenerd/jthreaddump/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jthreaddump/actions/workflows/ci.yml)

A small Java library for parsing thread dumps from `jstack` and `jcmd` output.

## Features

- **Parse jstack and jcmd output** - Automatic format detection
- **Rich data model** - Thread states, stack traces, locks, JNI refs, deadlocks
- **Multiple output formats** - Text, JSON, YAML
- **Java 21+** - Modern Java with records and pattern matching
- **Zero analysis** - Just parsing, you build the analysis

## Quick Start

### As a Library

```java
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import me.bechberger.jthreaddump.model.ThreadDump;

// Parse from string
ThreadDump dump = ThreadDumpParser.parse(threadDumpString);

// Access parsed data
System.out.println("Total threads: " + dump.threads().size());
for (var thread : dump.threads()) {
    System.out.println(thread.name() + " - " + thread.state());
}

// Check for deadlocks
if (dump.deadlockInfos() != null && !dump.deadlockInfos().isEmpty()) {
    System.out.println("Deadlocks detected!");
}
```

### Using JStackUtil

```java
import me.bechberger.jthreaddump.util.JStackUtil;

// Capture thread dump from live process
long pid = 12345;
String threadDump = JStackUtil.captureThreadDump(pid);

// Or use jcmd instead
String threadDump = JStackUtil.captureThreadDump(pid, true);

// Parse it
ThreadDump dump = ThreadDumpParser.parse(threadDump);
```

### CLI Tool

```bash
# Parse and display summary
jthreaddump dump.txt

# Output as JSON
jthreaddump dump.txt -o JSON

# Output as YAML
jthreaddump dump.txt -o YAML

# Quiet mode (minimal output)
jthreaddump dump.txt -q

# Read from stdin
jstack 12345 | jthreaddump -

# Verbose mode
jthreaddump dump.txt -v
```

## Installation

### Maven

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jthreaddump</artifactId>
    <version>0.3.0</version>
</dependency>
```

### Build from Source

```bash
git clone https://github.com/parttimenerd/jthreaddump
cd jthreaddump
mvn clean package

# Run CLI
java -jar target/jthreaddump.jar dump.txt
```

## Supported Formats

### jstack Output

```
"main" #1 prio=5 tid=0x00007f8b1c00a800 nid=0x1703 runnable [0x0000700001234000]
   java.lang.Thread.State: RUNNABLE
   at java.io.FileInputStream.readBytes(Native Method)
   at java.io.FileInputStream.read(FileInputStream.java:255)
```

### jcmd Thread.print Output

```
"main" #1 daemon prio=5 tid=0x1 nid=0x2003 runnable [0x700001234000]
   java.lang.Thread.State: RUNNABLE
   at com.example.Main.main(Main.java:10)
   - locked <0x00000007bff00000> (a java.lang.Object)
```

Both formats automatically detected and parsed.

## Data Model

The parser extracts:

- **Thread Info**: name, ID, state, priority, daemon flag, native ID
- **Stack Traces**: class, method, file, line number, native flag
- **Locks**: monitors held/waiting, object addresses, lock types
- **JNI References**: global/weak ref counts and memory
- **Deadlocks**: detected cycles with involved threads
- **Timing**: CPU time, elapsed time
- **JVM Info**: version, runtime details

All data exposed as immutable Java records.

## Examples

### Example 1: Find BLOCKED threads

```java
ThreadDump dump = ThreadDumpParser.parse(dumpText);

dump.threads().stream()
    .filter(t -> t.state() == Thread.State.BLOCKED)
    .forEach(t -> System.out.println(t.name() + " is blocked"));
```

### Example 2: Find threads consuming most CPU time

```java
dump.threads().stream()
    .filter(t -> t.cpuTimeSec() != null)
    .sorted((a, b) -> Double.compare(b.cpuTimeSec(), a.cpuTimeSec()))
    .limit(10)
    .forEach(t -> System.out.printf("%s: %.2f seconds CPU%n", 
        t.name(), t.cpuTimeSec()));
```

### Example 3: Compare thread counts

```java
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

ThreadDump dump1 = ThreadDumpParser.parse(Files.readString(Path.of("dump1.txt")));
ThreadDump dump2 = ThreadDumpParser.parse(Files.readString(Path.of("dump2.txt")));

System.out.println("Thread delta: " +
                (dump2.threads().size() - dump1.threads().size()));
```

### Example 4: Find long-running threads

```java
dump.threads().stream()
    .filter(t -> t.elapsedTimeSec() != null)
    .filter(t -> t.elapsedTimeSec() > 60.0) // > 1 minute
    .forEach(t -> System.out.printf("%s: %.2f seconds%n", 
        t.name(), t.elapsedTimeSec()));
```

## CLI Usage

```
Usage: jthreaddump [-hqvV] [-o=<outputFormat>] [<dumpFile>]
Thread Dump Parser Library - Parse Java thread dumps from jstack/jcmd output

      [<dumpFile>]   Path to the thread dump file (or '-' for stdin)
  -h, --help         Show this help message and exit.
  -o, --output=<outputFormat>
                     Output format: TEXT, JSON, YAML (default: TEXT)
  -q, --quiet        Minimal output (suppress headers in text mode)
  -v, --verbose      Enable verbose output
  -V, --version      Print version information and exit.
```

## Testing

```bash
# Run all tests
mvn test
```

## Deployment

Use the included Python script to automate version bumps and releases:

```bash
# Bump minor version (0.0.0 -> 0.2.0), run tests, build
./release.py

# Bump patch version (0.0.0 -> 0.1.1)
./release.py --patch

# Full release: bump, test, build, deploy, commit, tag, push
./release.py --deploy --push

# Dry run to see what would happen
./release.py --dry-run
```

Support, Feedback, Contributing
-------------------------------
This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jthreaddump/issues) issues.
Contribution and feedback are encouraged and always welcome.
For more information about how to contribute, the project structure,
as well as additional contribution information, see our Contribution Guidelines.


License
-------
MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors