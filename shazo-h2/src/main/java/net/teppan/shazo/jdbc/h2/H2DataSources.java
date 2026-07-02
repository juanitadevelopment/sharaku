package net.teppan.shazo.jdbc.h2;

import org.h2.jdbcx.JdbcConnectionPool;

import javax.sql.DataSource;

/**
 * Factory for H2 {@link DataSource} instances — file-based, in-memory, or
 * connected to a running H2 server.
 *
 * <p>Lives in the separate {@code shazo-h2} artifact so that core {@code shazo}
 * carries no H2 dependency: only applications that opt into this convenience
 * pull H2 onto their classpath. Callers that supply their own
 * {@link DataSource} (HikariCP over PostgreSQL, etc.) need neither this class
 * nor {@code shazo-h2}.
 *
 * <p>All modes use H2's PostgreSQL compatibility settings by default
 * ({@code MODE=PostgreSQL}, {@code DATABASE_TO_LOWER=TRUE},
 * {@code DEFAULT_NULL_ORDERING=HIGH}), so SQL written against an H2 database
 * (e.g. in development or tests) will largely work unchanged against a
 * PostgreSQL production server.
 *
 * <p>The returned {@code DataSource} is backed by H2's built-in connection
 * pool ({@link JdbcConnectionPool}). For higher-throughput workloads, wrap
 * the result in HikariCP or another production-grade pool.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * // Persistent, file-based database
 * DataSource ds = H2DataSources.file("./data/myapp");
 *
 * // In-memory database (tests)
 * DataSource ds = H2DataSources.inMemory("testdb");
 *
 * // Connect to a running H2 server
 * DataSource ds = H2DataSources.server("localhost", 9092, "myapp");
 * }</pre>
 *
 * @see net.teppan.shazo.jdbc.SchemaManager
 * @see net.teppan.shazo.jdbc.JdbcRepository
 */
public final class H2DataSources {

    /**
     * PostgreSQL-compatible H2 options applied to every URL.
     *
     * <ul>
     *   <li>{@code MODE=PostgreSQL} — SQL dialect compatibility</li>
     *   <li>{@code DATABASE_TO_LOWER=TRUE} — identifiers are case-insensitive,
     *       stored lower-case (matches PostgreSQL defaults)</li>
     *   <li>{@code DEFAULT_NULL_ORDERING=HIGH} — NULLs sort last in ASC order,
     *       matching PostgreSQL behaviour</li>
     * </ul>
     */
    public static final String PG_COMPAT =
        "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    /**
     * Returns a file-based, persistent H2 {@code DataSource}.
     *
     * <p>The database is stored in the two files {@code <path>.mv.db} and
     * {@code <path>.trace.db}. Relative paths are resolved against the
     * JVM's working directory.
     *
     * @param path the file path prefix (e.g. {@code "./data/myapp"});
     *             never {@code null}
     * @return a pooled {@code DataSource} backed by the file database
     */
    public static DataSource file(String path) {
        return pool("jdbc:h2:file:" + path + ";" + PG_COMPAT);
    }

    /**
     * Returns an in-memory H2 {@code DataSource}.
     *
     * <p>The database is retained for the lifetime of the JVM
     * ({@code DB_CLOSE_DELAY=-1}). Useful for unit and integration tests.
     *
     * @param name a unique name for this in-memory database; never {@code null}
     * @return a pooled {@code DataSource} backed by an in-memory database
     */
    public static DataSource inMemory(String name) {
        return pool("jdbc:h2:mem:" + name
            + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;" + PG_COMPAT);
    }

    /**
     * Returns a {@code DataSource} that connects to a remote H2 server.
     *
     * <p>The H2 server must already be running (start it with
     * {@code java -cp h2.jar org.h2.tools.Server -tcp}).
     *
     * @param host     the hostname or IP address of the H2 server
     * @param port     the TCP port (H2 default: {@code 9092})
     * @param database the database name as registered on the server
     * @return a pooled {@code DataSource} connected to the remote database
     */
    public static DataSource server(String host, int port, String database) {
        return pool("jdbc:h2:tcp://" + host + ":" + port + "/" + database
            + ";" + PG_COMPAT);
    }

    private static DataSource pool(String url) {
        return JdbcConnectionPool.create(url, "sa", "");
    }

    private H2DataSources() {}
}
