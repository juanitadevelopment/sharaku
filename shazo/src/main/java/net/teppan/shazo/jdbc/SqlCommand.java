package net.teppan.shazo.jdbc;

import net.teppan.shazo.Command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A parameterized SQL statement for use with {@link JdbcRepository}.
 *
 * <p>Each command carries a {@linkplain Command#name() name} used to key its
 * result when a single operation runs several queries — for example an aggregate
 * {@code retrieve} that fetches a root and its children with separate commands.
 * {@link #of} names the command {@code "result"} (fine for single-command
 * operations); {@link #named} sets an explicit name.
 *
 * <pre>{@code
 * // single query
 * List.of(SqlCommand.of("SELECT 1 FROM person WHERE id = ?", person.id()))
 *
 * // aggregate: root + children, each named for the infuser
 * List.of(
 *     SqlCommand.named("order", "SELECT * FROM orders WHERE id = ?", id),
 *     SqlCommand.named("lines", "SELECT * FROM order_line WHERE order_id = ?", id))
 * }</pre>
 *
 * @param name       the result name (see {@link Command#name()})
 * @param statement  the SQL text with {@code ?} as positional placeholders
 * @param parameters the bind values in placeholder order; the list itself is
 *                   never {@code null}, but an element may be {@code null}
 *                   for a nullable column ({@link java.sql.PreparedStatement}
 *                   binds a {@code null} element as SQL {@code NULL})
 * @see JdbcRepository
 */
public record SqlCommand(String name, String statement, List<Object> parameters) implements Command {

    /**
     * Compact constructor — defensively copies the parameter list into an
     * unmodifiable list that (unlike {@link List#copyOf}) permits {@code null}
     * elements, since a bind value for a nullable column is legitimately
     * {@code null}.
     */
    public SqlCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(statement, "statement");
        parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    /**
     * Creates a command named {@code "result"} with positional bind values.
     * An element of {@code params} may be {@code null} for a nullable column.
     *
     * @param statement the SQL text
     * @param params    the bind values
     * @return a new {@code SqlCommand}
     */
    public static SqlCommand of(String statement, Object... params) {
        return new SqlCommand("result", statement, Arrays.asList(params));
    }

    /**
     * Creates a named command with positional bind values, for an operation that
     * runs several commands whose results an {@link net.teppan.shazo.Infuser}
     * assembles by name. An element of {@code params} may be {@code null} for a
     * nullable column.
     *
     * @param name      the result name
     * @param statement the SQL text
     * @param params    the bind values
     * @return a new {@code SqlCommand}
     */
    public static SqlCommand named(String name, String statement, Object... params) {
        return new SqlCommand(name, statement, Arrays.asList(params));
    }
}
