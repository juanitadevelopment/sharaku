package net.teppan.shazo.file;

import net.teppan.shazo.Command;
import net.teppan.shazo.RawResult;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * File-system storage directives for use with {@link FileRepository}.
 *
 * <p>{@code FileCommand} is a sealed interface whose four variants cover the
 * full set of file operations needed to implement any {@link net.teppan.shazo.Repository}:
 *
 * <ul>
 *   <li>{@link Write} — write content to a named file</li>
 *   <li>{@link Delete} — delete a named file (no-op if absent)</li>
 *   <li>{@link Read} — read a named file; result row key: {@code "_content"}</li>
 *   <li>{@link List} — list files matching a glob, parse each, filter by predicate</li>
 * </ul>
 *
 * <p>As a custom extension of {@link Command}, {@code FileCommand} lives in
 * the {@code net.teppan.shazo.file} package and requires no modification to
 * the framework's {@code Command} interface.  Other storage backends
 * (e.g. AWS S3, Redis) follow the same pattern: define a sealed interface
 * that extends {@link Command} in their own package.
 *
 * @see FileRepository
 * @see Command
 */
public sealed interface FileCommand extends Command
        permits FileCommand.Write, FileCommand.Delete, FileCommand.Read, FileCommand.List {

    /**
     * Write {@code content} to the file named {@code name} in the
     * repository's base directory, replacing it if it already exists.
     *
     * @param name    the file name (not a path); never {@code null}
     * @param content the text to write; never {@code null}
     */
    record Write(String name, String content) implements FileCommand {
        /** Compact constructor — validates required fields. */
        public Write {
            Objects.requireNonNull(name,    "name");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * Delete the file named {@code name} from the repository's base directory.
     * No-op if the file does not exist.
     *
     * @param name the file name; never {@code null}
     */
    record Delete(String name) implements FileCommand {
        /** Compact constructor — validates required fields. */
        public Delete {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Read the file named {@code name} from the repository's base directory.
     *
     * <p>Result: one row {@code {"_content": <text>}} if the file exists;
     * an empty result if the file does not exist.
     *
     * @param name the file name; never {@code null}
     */
    record Read(String name) implements FileCommand {
        /** Compact constructor — validates required fields. */
        public Read {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * List all files in the repository's base directory whose names match
     * {@code glob} (e.g. {@code "*.memo"}), parse each file's text content
     * via {@code rowParser}, and include only rows for which {@code predicate}
     * returns {@code true}.
     *
     * <p>The row maps produced by {@code rowParser} are collected into
     * {@link RawResult}; keys and value types are determined by the
     * {@code rowParser} and must align with what the {@link net.teppan.shazo.Describer}'s
     * {@link net.teppan.shazo.Infuser} expects.
     *
     * @param glob      glob pattern for file names; never {@code null}
     * @param rowParser converts raw file text to a row map; never {@code null}
     * @param predicate selects which parsed rows to include; never {@code null}
     */
    record List(
            String glob,
            Function<String, Map<String, Object>> rowParser,
            Predicate<Map<String, Object>> predicate) implements FileCommand {

        /** Compact constructor — validates required fields. */
        public List {
            Objects.requireNonNull(glob,      "glob");
            Objects.requireNonNull(rowParser, "rowParser");
            Objects.requireNonNull(predicate, "predicate");
        }

        /**
         * Creates a {@code List} command that includes all files matching
         * the glob (no predicate filtering).
         *
         * @param glob      the glob pattern
         * @param rowParser the file content parser
         * @return a new {@code List} command
         */
        public static List of(String glob,
                              Function<String, Map<String, Object>> rowParser) {
            return new List(glob, rowParser, row -> true);
        }
    }
}
