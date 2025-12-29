package me.bechberger.jstall;

import me.bechberger.jstall.cli.DiffCommand;
import me.bechberger.jstall.cli.ParseCommand;
import me.bechberger.jstall.cli.StallCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for jstall CLI
 */
@Command(
        name = "jstall",
        description = "Simple Thread Dump Analyzer",
        version = "0.1.0",
        mixinStandardHelpOptions = true,
        subcommands = {
                ParseCommand.class,
                DiffCommand.class,
                StallCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class Main implements Runnable {

    @Override
    public void run() {
        // Show help if no subcommand is provided
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}