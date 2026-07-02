package net.teppan.shazo.jdbc;

import org.h2.jdbcx.JdbcConnectionPool;

import javax.sql.DataSource;

/**
 * Test-only H2 {@link DataSource} factory for core shazo tests.
 *
 * <p>Core shazo no longer depends on H2 in its main artifact — the public
 * convenience {@code H2DataSources} lives in the separate {@code shazo-h2}
 * module. Core tests cannot use it without a dependency cycle, so this tiny
 * helper mirrors the same PostgreSQL-compatible H2 URLs in test scope
 * ({@code com.h2database:h2} is a {@code testImplementation}).
 */
final class TestH2 {

    private static final String PG_COMPAT =
        "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    static DataSource inMemory(String name) {
        return JdbcConnectionPool.create(
            "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;" + PG_COMPAT,
            "sa", "");
    }

    static DataSource file(String path) {
        return JdbcConnectionPool.create("jdbc:h2:file:" + path + ";" + PG_COMPAT, "sa", "");
    }

    private TestH2() {}
}
