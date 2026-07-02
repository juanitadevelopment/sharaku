package net.teppan.shazo.jdbc.h2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link H2DataSources}.
 */
class H2DataSourcesTest {

    @Test
    void inMemoryDataSourceAcceptsConnections() throws SQLException {
        var ds = H2DataSources.inMemory("ds_test_basic");
        try (var conn = ds.getConnection()) {
            assertThat(conn.isClosed()).isFalse();
        }
    }

    @Test
    void inMemoryDataSourceExecutesDdl() throws SQLException {
        var ds = H2DataSources.inMemory("ds_test_ddl");
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t (id INT PRIMARY KEY)");
            stmt.execute("INSERT INTO t VALUES (1)");
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM t")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void fileDataSourcePersistsBetweenConnections(@TempDir Path dir) throws SQLException {
        var path = dir.resolve("testdb").toString();
        var ds   = H2DataSources.file(path);

        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE val (v INT)");
            stmt.execute("INSERT INTO val VALUES (42)");
        }

        // Re-open via a new DataSource pointing to the same file
        var ds2 = H2DataSources.file(path);
        try (var conn = ds2.getConnection();
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery("SELECT v FROM val")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(42);
        }
    }

    @Test
    void postgresCompatModeIsEnabled() throws SQLException {
        var ds = H2DataSources.inMemory("ds_test_pg_compat");
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            // ILIKE is a PostgreSQL extension; works in H2 PostgreSQL mode
            stmt.execute("CREATE TABLE words (w VARCHAR(50))");
            stmt.execute("INSERT INTO words VALUES ('Hello')");
            try (var rs = stmt.executeQuery("SELECT w FROM words WHERE w ILIKE 'hello'")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }
}
