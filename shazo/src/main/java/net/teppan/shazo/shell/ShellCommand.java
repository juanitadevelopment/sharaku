package net.teppan.shazo.shell;

import net.teppan.shazo.Command;

import java.util.List;
import java.util.Objects;

/**
 * An external process invocation for use with {@link ShellRepository}.
 *
 * <pre>{@code
 * List.of(ShellCommand.of("grep", pattern, "/var/log/app.log"))
 * List.of(ShellCommand.of("tail", "-n", "100", "/var/log/app.log"))
 * }</pre>
 *
 * @param executable the program name or absolute path
 * @param arguments  command-line arguments; never {@code null}
 * @see ShellRepository
 */
public record ShellCommand(String executable, List<String> arguments) implements Command {

    /** Compact constructor — defensively copies the argument list. */
    public ShellCommand {
        Objects.requireNonNull(executable, "executable");
        arguments = List.copyOf(arguments);
    }

    /**
     * Creates a {@code ShellCommand} with arguments.
     *
     * @param executable the program to execute
     * @param arguments  the command-line arguments
     * @return a new {@code ShellCommand}
     */
    public static ShellCommand of(String executable, String... arguments) {
        return new ShellCommand(executable, List.of(arguments));
    }
}
