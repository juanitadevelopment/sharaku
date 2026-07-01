package net.teppan.shazo.shell;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a single line of shell-command stdout to a column-name-to-value
 * mapping for use in a {@link net.teppan.shazo.RawResult}.
 *
 * <p>{@code LineParser} is a {@link FunctionalInterface}; lambdas are valid
 * implementations.
 *
 * <h2>Built-in parsers</h2>
 * <ul>
 *   <li>{@link #byLine()} — each line → {@code {"line": "<text>"}}</li>
 *   <li>{@link #tabDelimited(String...)} — TSV with named columns</li>
 *   <li>{@link #delimited(String, String...)} — arbitrary regex delimiter</li>
 * </ul>
 *
 * @see ShellRepository
 */
@FunctionalInterface
public interface LineParser {

    /**
     * Parses a single non-blank stdout line into a row.
     *
     * @param line a non-blank line of stdout; never {@code null}
     * @return a column-name-to-value map; never {@code null}
     */
    Map<String, Object> parse(String line);

    // ── Built-in parsers ─────────────────────────────────────────────────────

    /**
     * Returns a parser that maps each line to {@code {"line": "<the line>"}}.
     *
     * <p>This is the default used by
     * {@link ShellRepository#ShellRepository(net.teppan.shazo.Describer)}.
     *
     * @return the by-line parser
     */
    static LineParser byLine() {
        return line -> Map.of("line", line);
    }

    /**
     * Returns a parser that splits each line on tab ({@code \t}) and maps
     * the parts to the given column names in order.
     *
     * <p>Parts beyond the last column name are ignored. Missing parts
     * (fewer fields than columns) map to an empty string.
     *
     * @param columns the column names; must not be empty
     * @return a tab-delimited parser
     * @throws IllegalArgumentException if {@code columns} is empty
     */
    static LineParser tabDelimited(String... columns) {
        if (columns.length == 0) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        return delimited("\t", columns);
    }

    /**
     * Returns a parser that splits each line on the given delimiter regex and
     * maps the parts to the given column names in order.
     *
     * <p>Parts beyond the last column name are ignored. Missing parts
     * (fewer fields than columns) map to an empty string.
     *
     * @param delimiter the field delimiter as a regex
     * @param columns   the column names; must not be empty
     * @return a delimiter-based parser
     * @throws IllegalArgumentException if {@code columns} is empty
     */
    static LineParser delimited(String delimiter, String... columns) {
        if (columns.length == 0) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        return line -> {
            var parts = line.split(delimiter, -1);
            var row   = new LinkedHashMap<String, Object>(columns.length);
            for (int i = 0; i < columns.length; i++) {
                row.put(columns[i], i < parts.length ? parts[i] : "");
            }
            return Map.copyOf(row);
        };
    }
}
