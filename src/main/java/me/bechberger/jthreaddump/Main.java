package me.bechberger.jthreaddump;

import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main entry point for jthreaddump CLI - parses thread dumps and outputs in various formats
 */
public class Main {

    // simple CLI fields (positional file, and verbose flag)
    private final String dumpFile; // '-' means read from stdin
    private final boolean quiet;
    private final boolean verbose;

    public Main(String dumpFile, boolean quiet, boolean verbose) {
        this.dumpFile = dumpFile;
        this.quiet = quiet;
        this.verbose = verbose;
    }

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

            // Only print the main output when not in quiet mode
            if (!quiet) {
                outputText(dump);
            } else if (verbose) {
                // In quiet mode we suppress normal output, but allow a verbose log if requested
                verboseLog("Quiet mode enabled; skipping standard output.");
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

    private void outputText(ThreadDump dump) {
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

    private static void printUsage() {
        String usage = "Usage: jthreaddump [OPTIONS] [file]\n" +
                "  file       Input file to read (use '-' or omit to read from stdin)\n" +
                "Options:\n" +
                "  -h, --help     Show this help message and exit\n" +
                "  -v, --verbose  Enable verbose logging\n" +
                "  -q, --quiet    Suppress standard output (errors still printed)\n";
        System.out.print(usage);
    }

    public static void main(String[] args) {
        // Minimal vanilla Java CLI parsing
        String positional = null;
        boolean endOfOptions = false;
        boolean verbose = false;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!endOfOptions && a.equals("--")) {
                endOfOptions = true;
                continue;
            }
            if (!endOfOptions && (a.equals("-h") || a.equals("--help"))) {
                printUsage();
                System.exit(0);
            } else if (!endOfOptions && (a.equals("-v") || a.equals("--verbose"))) {
                verbose = true;
            } else if (!endOfOptions && (a.equals("-q") || a.equals("--quiet"))) {
                quiet = true;
            } else if (!a.startsWith("-") || endOfOptions) {
                // first positional arg is file
                if (positional == null) {
                    positional = a;
                } else {
                    // ignore additional positionals for now
                }
            } else {
                System.err.println("Unknown option: " + a);
                printUsage();
                System.exit(2);
            }
        }

        Main program = new Main(positional, quiet, verbose);

        Integer exitCode = program.call();
        System.exit(exitCode == null ? 0 : exitCode);
    }
}