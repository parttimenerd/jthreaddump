package me.bechberger.jstall.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.parser.ThreadDumpParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Parse command - parses a single thread dump and outputs in various formats
 */
@Command(
        name = "parse",
        description = "Parse a single thread dump file and output in structured format"
)
public class ParseCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the thread dump file")
    private Path dumpFile;

    @Option(names = {"-o", "--output"}, description = "Output format: text, json, yaml (default: text)")
    private OutputFormat outputFormat = OutputFormat.TEXT;

    @Option(names = {"-p", "--pretty"}, description = "Pretty print JSON/YAML output (default: true)")
    private boolean prettyPrint = true;

    public enum OutputFormat {
        TEXT, JSON, YAML
    }

    @Override
    public Integer call() throws Exception {
        // Validate input file
        if (!Files.exists(dumpFile)) {
            System.err.println("Error: File not found: " + dumpFile);
            return 1;
        }

        if (!Files.isRegularFile(dumpFile)) {
            System.err.println("Error: Not a regular file: " + dumpFile);
            return 1;
        }

        try {
            // Read and parse the dump
            String content = Files.readString(dumpFile);
            ThreadDumpParser parser = new ThreadDumpParser();
            ThreadDump dump = parser.parse(content);

            // Output based on format
            switch (outputFormat) {
                case JSON -> outputJson(dump);
                case YAML -> outputYaml(dump);
                case TEXT -> outputText(dump);
            }

            return 0;

        } catch (IOException e) {
            System.err.println("Error reading or parsing file: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void outputJson(ThreadDump dump) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        if (prettyPrint) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        mapper.findAndRegisterModules();
        System.out.println(mapper.writeValueAsString(dump));
    }

    private void outputYaml(ThreadDump dump) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        System.out.println(mapper.writeValueAsString(dump));
    }

    private void outputText(ThreadDump dump) {
        System.out.println("=== Thread Dump Analysis ===");
        System.out.println();

        if (dump.jvmInfo() != null) {
            System.out.println("JVM Info: " + dump.jvmInfo());
        }
        System.out.println("Source: " + dump.sourceType());
        System.out.println("Timestamp: " + dump.timestamp());
        System.out.println("Total Threads: " + dump.threads().size());
        System.out.println();

        // Thread state summary
        var stateCount = dump.threads().stream()
                .filter(t -> t.state() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        me.bechberger.jstall.model.ThreadInfo::state,
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

        // Individual threads
        System.out.println("Threads:");
        System.out.println("--------");
        for (var thread : dump.threads()) {
            printThread(thread);
            System.out.println();
        }
    }

    private void printThread(me.bechberger.jstall.model.ThreadInfo thread) {
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

        if (thread.cpuTimeMs() != null) {
            System.out.println("  CPU time: " + thread.cpuTimeMs() + "ms");
        }
        if (thread.elapsedTimeMs() != null) {
            System.out.println("  Elapsed: " + thread.elapsedTimeMs() + "ms");
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
}