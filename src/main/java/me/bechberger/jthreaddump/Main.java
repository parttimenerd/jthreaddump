package me.bechberger.jthreaddump;

import me.bechberger.jthreaddump.model.PrettyPrinter;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal CLI: pretty-print a thread dump from a file or stdin.
 */
public final class Main {

    private Main() {
    }

    private static void printUsage() {
        System.out.print("""
                Usage: jthreaddump [--help] [file]
                  file       Input file to read (use '-' or omit to read from stdin)
                Options:
                  -h, --help  Show this help message and exit
                """);
    }

    private static String parseFileArg(String[] argv) {
        String file = null;
        boolean endOfOptions = false;

        for (String a : argv) {
            if (!endOfOptions && a.equals("--")) {
                endOfOptions = true;
                continue;
            }

            if (!endOfOptions && (a.equals("-h") || a.equals("--help"))) {
                printUsage();
                System.exit(0);
            }

            if (endOfOptions || !a.startsWith("-")) {
                if (file != null) {
                    throw new IllegalArgumentException("Too many arguments");
                }
                file = a;
                continue;
            }

            throw new IllegalArgumentException("Unknown option: " + a);
        }

        return file;
    }

    private static String readInput(String file) throws IOException {
        if (file == null || file.equals("-")) {
            return new String(System.in.readAllBytes());
        }
        return Files.readString(Path.of(file));
    }

    public static void main(String[] argv) throws IOException {
        final String file;
        try {
            file = parseFileArg(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        String content = readInput(file);
        ThreadDump dump = ThreadDumpParser.parse(content);
        System.out.println(PrettyPrinter.dump(dump));
    }
}