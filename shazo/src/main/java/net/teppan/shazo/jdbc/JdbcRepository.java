package net.teppan.shazo.jdbc;

import net.teppan.shazo.AbstractRepository;
import net.teppan.shazo.Describer;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.ShazoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link net.teppan.shazo.Repository} implementation backed by a relational
 * database via JDBC.
 *
 * <p>{@code JdbcRepository} accepts a {@link DataSource} (supplied by the
 * caller — any JDBC pool such as HikariCP works) and a {@link Describer} that
 * produces {@link SqlCommand} directives.  Each call to
 * {@link #contains}/{@link #store}/{@link #delete}/{@link #retrieve}/{@link #catalog}
 * borrows a connection from the pool, executes the commands, and returns it.
 *
 * <h2>Creating a repository</h2>
 * <pre>{@code
 * DataSource ds = ...; // HikariCP, etc.
 *
 * Describer<Person, SqlCommand> describer = Describer.<Person, SqlCommand>builder()
 *     .contains(p  -> List.of(SqlCommand.of("SELECT 1 FROM person WHERE id = ?", p.id())))
 *     .store(p     -> List.of(SqlCommand.of(
 *         "MERGE INTO person (id, name, age) VALUES (?, ?, ?)", p.id(), p.name(), p.age())))
 *     .delete(p    -> List.of(SqlCommand.of("DELETE FROM person WHERE id = ?", p.id())))
 *     .retrieve(p  -> List.of(SqlCommand.of(
 *         "SELECT id, name, age FROM person WHERE id = ?", p.id())))
 *     .catalog(p   -> List.of(SqlCommand.of("SELECT id, name, age FROM person")))
 *     .infuser(result -> result.first().map(row -> new Person(
 *         (String) row.get("ID"), (String) row.get("NAME"),
 *         ((Number) row.get("AGE")).intValue())).orElseThrow())
 *     .gatherer(result -> result.rows().stream()
 *         .map(row -> new Person(
 *             (String) row.get("ID"), (String) row.get("NAME"),
 *             ((Number) row.get("AGE")).intValue())).toList())
 *     .build();
 *
 * var repo = new JdbcRepository<>(ds, describer);
 * }</pre>
 *
 * <h2>Transactions</h2>
 * <p>Use {@link #transact} to run multiple operations atomically:
 * <pre>{@code
 * int count = repo.transact(r -> {
 *     r.store(alice);
 *     r.store(bob);
 *     return 2;
 * });
 * }</pre>
 *
 * @param <T> the domain type managed by this repository
 * @see Describer
 * @see StorageTask
 */
public final class JdbcRepository<T> extends AbstractRepository<T, SqlCommand> {

    private static final Logger log = LoggerFactory.getLogger(JdbcRepository.class);

    private final DataSource dataSource;

    /**
     * Constructs a {@code JdbcRepository}.
     *
     * @param dataSource the JDBC data source; never {@code null}
     * @param describer  the describer for domain type {@code T}; never {@code null}
     */
    public JdbcRepository(DataSource dataSource, Describer<T, SqlCommand> describer) {
        super(describer);
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    // ── Core execution ───────────────────────────────────────────────────────

    @Override
    protected RawResult execute(List<SqlCommand> commands) throws ShazoException {
        try (var conn = dataSource.getConnection()) {
            return executeOnConnection(conn, commands);
        } catch (ShazoException e) {
            throw e;
        } catch (SQLException e) {
            throw new ShazoException("Failed to obtain JDBC connection", e);
        }
    }

    // ── Transactions ─────────────────────────────────────────────────────────

    /**
     * Executes a {@link StorageTask} within a single JDBC transaction.
     *
     * <p>A connection is borrowed from the pool, auto-commit is disabled, and
     * the task receives a repository bound to that connection.  The transaction
     * commits when the task returns normally; any exception triggers a rollback
     * before the exception propagates.
     *
     * @param task the unit of work to execute
     * @param <R>  the task's return type
     * @return the value returned by the task
     * @throws ShazoException if the task fails or the connection is unavailable
     */
    public <R> R transact(StorageTask<T, R> task) throws ShazoException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var bound = new BoundJdbcRepository<>(conn, describer());
            try {
                var result = task.execute(bound);
                conn.commit();
                return result;
            } catch (ShazoException e) {
                safeRollback(conn);
                throw e;
            } catch (Exception e) {
                safeRollback(conn);
                throw new ShazoException("Transaction failed", e);
            }
        } catch (ShazoException e) {
            throw e;
        } catch (SQLException e) {
            throw new ShazoException("Failed to obtain JDBC connection for transaction", e);
        }
    }

    // ── Internal SQL execution (package-accessible for BoundJdbcRepository) ─

    static RawResult executeOnConnection(Connection conn, List<SqlCommand> commands)
            throws ShazoException {
        var rows = new ArrayList<Map<String, Object>>();
        for (var sql : commands) {
            log.debug("SQL: {}", sql.statement());
            rows.addAll(runSql(conn, sql));
        }
        return RawResult.of(rows);
    }

    private static List<Map<String, Object>> runSql(Connection conn, SqlCommand sql)
            throws ShazoException {
        try (var ps = conn.prepareStatement(sql.statement())) {
            var params = sql.parameters();
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            if (ps.execute()) {
                return collectRows(ps.getResultSet());
            }
            return List.of();
        } catch (SQLException e) {
            throw new ShazoException(
                "SQL execution failed [" + sql.statement() + "]", e);
        }
    }

    private static List<Map<String, Object>> collectRows(ResultSet rs)
            throws SQLException {
        var rows = new ArrayList<Map<String, Object>>();
        var meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            var row = new LinkedHashMap<String, Object>(cols);
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnName(i), rs.getObject(i));
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return rows;
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ex) {
            log.warn("Rollback failed", ex);
        }
    }
}
