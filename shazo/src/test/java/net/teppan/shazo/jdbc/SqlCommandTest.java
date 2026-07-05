package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.ShazoException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link SqlCommand} must accept a {@code null} bind value — a nullable
 * column's value is legitimately {@code null} — even though the varargs
 * factories start from an array and the compact constructor defensively
 * copies the list. {@link List#of} and {@link List#copyOf} both reject
 * {@code null} elements, so this is exercised explicitly rather than left to
 * incidental coverage from a describer that happens not to have one.
 */
class SqlCommandTest {

    @Test
    void ofAcceptsANullParameter() {
        assertThatCode(() -> SqlCommand.of("UPDATE t SET a = ? WHERE id = ?", null, 1))
            .doesNotThrowAnyException();
    }

    @Test
    void namedAcceptsANullParameter() {
        assertThatCode(() -> SqlCommand.named("result", "UPDATE t SET a = ? WHERE id = ?", null, 1))
            .doesNotThrowAnyException();
    }

    @Test
    void canonicalConstructorAcceptsANullParameter() {
        List<Object> params = Arrays.asList("x", null, "z");   // List.of/copyOf would reject the null
        assertThatCode(() -> new SqlCommand("result", "...", params)).doesNotThrowAnyException();
    }

    @Test
    void parametersPreservesANullElementAtItsPosition() {
        var cmd = SqlCommand.of("...", "a", null, "b");
        assertThat(cmd.parameters()).containsExactly("a", null, "b");
    }

    // ── Integration: a null bind value round-trips through an actual write/read ──

    record Widget(String id, String note) {}

    @Test
    void nullBindValueRoundTripsThroughH2() throws SQLException, ShazoException {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sqlcommand_null_test;DB_CLOSE_DELAY=-1");
        try (var conn = ((DataSource) ds).getConnection()) {
            conn.createStatement().execute(
                "CREATE TABLE widget (id VARCHAR(50) PRIMARY KEY, note VARCHAR(200) NULL)");
        }

        Describer<Widget, SqlCommand> describer = Describer.<Widget, SqlCommand>builder()
            .contains(w  -> List.of(SqlCommand.of("SELECT 1 FROM widget WHERE id = ?", w.id())))
            .store(w     -> List.of(SqlCommand.of(
                "MERGE INTO widget (id, note) KEY (id) VALUES (?, ?)", w.id(), w.note())))
            .delete(w    -> List.of(SqlCommand.of("DELETE FROM widget WHERE id = ?", w.id())))
            .retrieve(w  -> List.of(SqlCommand.of(
                "SELECT id, note FROM widget WHERE id = ?", w.id())))
            .catalog(w   -> List.of(SqlCommand.of("SELECT id, note FROM widget")))
            .infuser(results -> {
                var row = results.primary().first().orElseThrow();
                return new Widget((String) row.get("id"), (String) row.get("note"));
            })
            .build();

        var repo = new JdbcRepository<>((DataSource) ds, describer);
        repo.store(new Widget("w1", null));   // the write this test exists to cover

        assertThat(repo.retrieve(new Widget("w1", null))).contains(new Widget("w1", null));
    }
}
