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

    /**
     * A log- and error-safe description of this command that names the
     * {@code executable} and the <em>number</em> of arguments but never their
     * values.
     *
     * <p>Argument values routinely carry request-specific data — record ids,
     * file paths, tokens, credentials — supplied by the describer. Because
     * {@link ShellRepository} surfaces failures as {@code ShazoException}
     * messages, and those messages can be relayed to a remote caller by the
     * HTTP transport, the repository logs and reports this description rather
     * than the raw argument list, keeping argument values out of logs and
     * off the wire.
     *
     * @return e.g. {@code "grep [2 arg(s)]"}
     */
    public String safeDescription() {
        return executable + " [" + arguments.size() + " arg(s)]";
    }
}
