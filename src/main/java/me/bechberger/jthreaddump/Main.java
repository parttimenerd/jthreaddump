package me.bechberger.jthreaddump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Main entry point for jthreaddump CLI - parses thread dumps and outputs in various formats
 */
@Command(
        name = "jthreaddump",
        description = "Thread Dump Parser Library - Parse Java thread dumps from jstack/jcmd output",
        version = "0.3.0",
        mixinStandardHelpOptions = true
)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the thread dump file (or '-' for stdin)", arity = "0..1")
    private String dumpFile;

    @Option(names = {"-o", "--output"}, description = "Output format: text, json, yaml (default: ${DEFAULT-VALUE})")
    private OutputFormat outputFormat = OutputFormat.TEXT;


    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    private boolean verbose = false;

    @Option(names = {"-q", "--quiet"}, description = "Minimal output (suppress headers in text mode)")
    private boolean quiet = false;

    public enum OutputFormat {
        TEXT, JSON, YAML
    }

    @Override
    public Integer call() {
        try {
            // Read input
            String content;
            if (dumpFile == null || dumpFile.equals("-")) {
                // Read from stdin
                verboseLog("Reading from stdin...");
                content = new String(System.in.readAllBytes());
            } else {
                // Read from file
                Path path = Path.of(dumpFile);
                if (!Files.exists(path)) {
                    System.err.println("Error: File not found: " + dumpFile);
                    return 1;
                }
                if (!Files.isRegularFile(path)) {
                    System.err.println("Error: Not a regular file: " + dumpFile);
                    return 1;
                }
                verboseLog("Reading from file: " + dumpFile);
                content = Files.readString(path);
            }

            // Parse the dump
            verboseLog("Parsing thread dump...");
            ThreadDump dump = ThreadDumpParser.parse(content);
            verboseLog("Parsed " + dump.threads().size() + " threads");

            // Output based on format
            switch (outputFormat) {
                case JSON -> outputJson(dump);
                case YAML -> outputYaml(dump);
                case TEXT -> outputText(dump);
            }

            return 0;

        } catch (IOException e) {
            System.err.println("Error reading or parsing: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private void outputJson(ThreadDump dump) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.findAndRegisterModules();
        System.out.println(mapper.writeValueAsString(dump));
    }

    private void outputYaml(ThreadDump dump) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        System.out.println(mapper.writeValueAsString(dump));
    }

    private void outputText(ThreadDump dump) {
        if (!quiet) {
            System.out.println("=== Thread Dump Analysis ===");
            System.out.println();
        }

        if (!quiet && dump.jvmInfo() != null) {
            System.out.println("JVM Info: " + dump.jvmInfo());
        }
        if (!quiet) {
            System.out.println("Source: " + dump.sourceType());
            System.out.println("Timestamp: " + dump.timestamp());
        }
        System.out.println("Total Threads: " + dump.threads().size());
        System.out.println();

        // Thread state summary
        var stateCount = dump.threads().stream()
                .filter(t -> t.state() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        me.bechberger.jthreaddump.model.ThreadInfo::state,
                        java.util.stream.Collectors.counting()
                ));

        if (!stateCount.isEmpty()) {
            System.out.println("Thread States:");
            stateCount.forEach((state, count) ->
                    System.out.printf("  %s: %d%n", state, count)
            );
            System.out.println();
        }

        // JNI info
        if (dump.jniInfo() != null) {
            System.out.println("JNI Resources:");
            var jni = dump.jniInfo();
            if (jni.globalRefs() != null) {
                System.out.println("  Global refs: " + jni.globalRefs());
            }
            if (jni.weakRefs() != null) {
                System.out.println("  Weak refs: " + jni.weakRefs());
            }
            if (jni.globalRefsMemory() != null) {
                System.out.println("  Global refs memory: " + jni.globalRefsMemory());
            }
            if (jni.weakRefsMemory() != null) {
                System.out.println("  Weak refs memory: " + jni.weakRefsMemory());
            }
            System.out.println();
        }

        // Deadlocks
        if (dump.deadlockInfos() != null && !dump.deadlockInfos().isEmpty()) {
            System.out.println("⚠️  DEADLOCKS DETECTED: " + dump.deadlockInfos().size());
            System.out.println();
        }

        // Individual threads (limit in quiet mode)
        if (!quiet) {
            System.out.println("Threads:");
            System.out.println("--------");
            for (var thread : dump.threads()) {
                printThread(thread);
                System.out.println();
            }
        }
    }

    private void printThread(me.bechberger.jthreaddump.model.ThreadInfo thread) {
        System.out.printf("\"%s\" tid=%s nid=%s%n",
                thread.name(),
                thread.threadId() != null ? thread.threadId() : "?",
                thread.nativeId() != null ? String.format("0x%x", thread.nativeId()) : "?"
        );

        if (thread.daemon() != null && thread.daemon()) {
            System.out.print("  daemon");
        }
        if (thread.priority() != null) {
            System.out.print("  prio=" + thread.priority());
        }
        System.out.println();

        if (thread.state() != null) {
            System.out.println("  State: " + thread.state());
        }

        if (thread.cpuTimeSec() != null) {
            System.out.printf("  CPU time: %.2fs%n", thread.cpuTimeSec());
        }
        if (thread.elapsedTimeSec() != null) {
            System.out.printf("  Elapsed: %.2fs%n", thread.elapsedTimeSec());
        }

        if (!thread.locks().isEmpty()) {
            System.out.println("  Locks:");
            thread.locks().forEach(lock -> System.out.println("    " + lock));
        }

        if (!thread.stackTrace().isEmpty()) {
            System.out.println("  Stack:");
            thread.stackTrace().forEach(frame -> System.out.println("    " + frame));
        }
    }

    private void verboseLog(String message) {
        if (verbose) {
            System.err.println(message);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}