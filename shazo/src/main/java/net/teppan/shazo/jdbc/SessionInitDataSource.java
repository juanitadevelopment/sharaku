package net.teppan.shazo.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * A {@link DataSource} that runs one or more initialization statements on every
 * borrowed {@link Connection} before handing it out.
 *
 * <p>This is the seam for tenant strategies that share a database but scope each
 * connection to a tenant:
 *
 * <ul>
 *   <li><b>schema-per-tenant</b> — {@code SET SCHEMA "tenant_acme"} (the original
 *       framework's Oracle approach);</li>
 *   <li><b>row-level security</b> — {@code SET app.current_tenant = 'acme'}, with
 *       the database's RLS policies doing the isolation (PostgreSQL, SQL&nbsp;Server).</li>
 * </ul>
 *
 * For database-per-tenant, no wrapper is needed — route the tenant to its own
 * {@code DataSource}. A tenant router is then simply
 * {@code tenant -> new SessionInitDataSource(shared, "SET SCHEMA " + quote(tenant))}.
 *
 * <p>The init statements are executed via {@link java.sql.Statement#execute}; the
 * caller is responsible for their safety (e.g. validating/quoting a schema name,
 * which cannot be a bind parameter). Every other {@code DataSource} method is
 * forwarded to the delegate unchanged.
 */
public final class SessionInitDataSource implements DataSource {

    private final DataSource delegate;
    private final List<String> initStatements;

    /**
     * Wraps {@code delegate}, running the given statements on each borrowed
     * connection in order.
     *
     * @param delegate       the underlying data source; never {@code null}
     * @param initStatements the SQL statements to run on borrow; none {@code null}
     */
    public SessionInitDataSource(DataSource delegate, String... initStatements) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.initStatements = List.of(initStatements);
    }

    private Connection initialize(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            for (String sql : initStatements) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // surface the original failure
            }
            throw e;
        }
        return conn;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return initialize(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return initialize(delegate.getConnection(username, password));
    }

    // ── Plain forwarding ────────────────────────────────────────────────────────

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
