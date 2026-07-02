package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.MultipleFoundException;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.ShazoException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link JdbcRepository} using an H2 in-memory database.
 */
class JdbcRepositoryTest {

    // ── Domain type under test ───────────────────────────────────────────────

    record Person(String id, String name, int age) {}

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private DataSource dataSource;
    private JdbcRepository<Person> repo;

    @BeforeEach
    void setUp() throws SQLException {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:shazo_test;DB_CLOSE_DELAY=-1");
        dataSource = ds;

        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS person (
                    id   VARCHAR(50) PRIMARY KEY,
                    name VARCHAR(100),
                    age  INT
                )
                """);
        }

        // H2 returns column names in upper-case by default.
        Describer<Person, SqlCommand> describer = Describer.<Person, SqlCommand>builder()
            .contains(p -> List.of(SqlCommand.of(
                "SELECT 1 FROM person WHERE id = ?", p.id())))
            .store(p -> List.of(SqlCommand.of(
                "MERGE INTO person (id, name, age) VALUES (?, ?, ?)",
                p.id(), p.name(), p.age())))
            .delete(p -> List.of(SqlCommand.of(
                "DELETE FROM person WHERE id = ?", p.id())))
            .retrieve(p -> List.of(SqlCommand.of(
                "SELECT id, name, age FROM person WHERE id = ?", p.id())))
            .catalog(p -> List.of(SqlCommand.of(
                "SELECT id, name, age FROM person ORDER BY name")))
            .key(row -> new Person((String) row.get("ID"), null, 0))
            .infuser(result -> result.primary().first().map(row -> new Person(
                (String) row.get("ID"),
                (String) row.get("NAME"),
                ((Number) row.get("AGE")).intValue()
            )).orElseThrow())
            .build();

        repo = new JdbcRepository<>(dataSource, describer);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE person");
        }
    }

    // ── contains ─────────────────────────────────────────────────────────────

    @Test
    void containsReturnsFalseWhenAbsent() throws ShazoException {
        assertFalse(repo.contains(new Person("x", null, 0)));
    }

    @Test
    void containsReturnsTrueAfterStore() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));
        assertTrue(repo.contains(new Person("1", null, 0)));
    }

    // ── store + retrieve ──────────────────────────────────────────────────────

    @Test
    void storeAndRetrieveRoundTrip() throws ShazoException {
        var alice = new Person("1", "Alice", 30);
        repo.store(alice);

        assertThat(repo.retrieve(new Person("1", null, 0))).contains(alice);
    }

    @Test
    void retrieveReturnsEmptyWhenAbsent() throws ShazoException {
        assertThat(repo.retrieve(new Person("99", null, 0))).isEmpty();
    }

    @Test
    void storeReplacesPreviousEntity() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));
        repo.store(new Person("1", "Alice-Updated", 31));

        var result = repo.retrieve(new Person("1", null, 0));
        assertThat(result).contains(new Person("1", "Alice-Updated", 31));
    }

    // ── find ─────────────────────────────────────────────────────

    @Test
    void findThrowsNotFoundExceptionWhenAbsent() {
        assertThatThrownBy(() -> repo.find(new Person("missing", null, 0)))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("No entity found");
    }

    @Test
    void findReturnsEntityWhenPresent() throws ShazoException, NotFoundException {
        var bob = new Person("2", "Bob", 25);
        repo.store(bob);

        assertThat(repo.find(new Person("2", null, 0))).isEqualTo(bob);
    }

    @Test
    void findThrowsMultipleFoundWhenAmbiguous() throws ShazoException {
        // A describer whose retrieve key is a non-unique column (name).
        Describer<Person, SqlCommand> byName = Describer.<Person, SqlCommand>builder()
            .contains(p -> List.of(SqlCommand.of("SELECT 1 FROM person WHERE name = ?", p.name())))
            .store(p   -> List.of(SqlCommand.of(
                "MERGE INTO person (id, name, age) VALUES (?, ?, ?)", p.id(), p.name(), p.age())))
            .delete(p  -> List.of(SqlCommand.of("DELETE FROM person WHERE id = ?", p.id())))
            .retrieve(p -> List.of(SqlCommand.of(
                "SELECT id, name, age FROM person WHERE name = ?", p.name())))
            .catalog(p -> List.of(SqlCommand.of("SELECT id, name FROM person WHERE name = ?", p.name())))
            .key(row -> new Person((String) row.get("ID"), (String) row.get("NAME"), 0))
            .infuser(r -> r.primary().first().map(row -> new Person(
                (String) row.get("ID"), (String) row.get("NAME"),
                ((Number) row.get("AGE")).intValue())).orElseThrow())
            .build();
        var byNameRepo = new JdbcRepository<>(dataSource, byName);
        byNameRepo.store(new Person("1", "Dup", 30));
        byNameRepo.store(new Person("2", "Dup", 40));

        assertThatThrownBy(() -> byNameRepo.find(new Person(null, "Dup", 0)))
            .isInstanceOf(MultipleFoundException.class)
            .hasMessageContaining("found 2");
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesEntity() throws ShazoException {
        var alice = new Person("1", "Alice", 30);
        repo.store(alice);
        repo.delete(alice);

        assertFalse(repo.contains(new Person("1", null, 0)));
    }

    @Test
    void deleteIsIdempotentWhenAbsent() throws ShazoException {
        repo.delete(new Person("nonexistent", null, 0));
    }

    // ── catalog ───────────────────────────────────────────────────────────────

    @Test
    void gatherReturnsAllStoredEntities() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));
        repo.store(new Person("2", "Bob", 25));

        var results = repo.gather(new Person(null, null, 0));
        assertThat(results)
            .hasSize(2)
            .containsExactly(
                new Person("1", "Alice", 30),
                new Person("2", "Bob", 25));
    }

    @Test
    void gatherReturnsEmptyListWhenNoEntities() throws ShazoException {
        assertThat(repo.gather(new Person(null, null, 0))).isEmpty();
    }

    @Test
    void catalogReturnsTheRawTableWithoutMaterializingObjects() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));
        repo.store(new Person("2", "Bob", 25));

        var table = repo.catalog(new Person(null, null, 0));
        assertThat(table.size()).isEqualTo(2);
        // Named, case-insensitive columns — no Person objects created.
        assertThat(table.rows().getFirst().get("name")).isEqualTo("Alice");
        assertThat(table.rows().getFirst().get("NAME")).isEqualTo("Alice");
        assertThat(((Number) table.rows().getFirst().get("age")).intValue()).isEqualTo(30);
    }

    @Test
    void catalogReturnsEmptyTableWhenNoRows() throws ShazoException {
        assertThat(repo.catalog(new Person(null, null, 0)).isEmpty()).isTrue();
    }

    @Test
    void catalogPreservesSelectColumnOrder() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));

        // The fixture catalogs "SELECT id, name, age"; the row must iterate in
        // exactly that order (H2 reports the names upper-case), not alphabetically.
        var row = repo.catalog(new Person(null, null, 0)).rows().getFirst();
        assertThat(row.keySet()).containsExactly("ID", "NAME", "AGE");
    }

    @Test
    void columnAliasesAreHonored() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));

        // getColumnLabel: "AS person_key" must become the row key, portably.
        Describer<Person, SqlCommand> aliased = Describer.<Person, SqlCommand>builder()
            .contains(p -> List.of())
            .store(p    -> List.of())
            .delete(p   -> List.of())
            .retrieve(p -> List.of(SqlCommand.of(
                "SELECT id AS person_key, name FROM person WHERE id = ?", p.id())))
            .catalog(p  -> List.of(SqlCommand.of(
                "SELECT id AS person_key FROM person ORDER BY id")))
            .infuser(r  -> r.primary().first().map(row -> new Person(
                (String) row.get("person_key"), (String) row.get("name"), 0)).orElseThrow())
            .build();
        var aliasRepo = new JdbcRepository<>(dataSource, aliased);

        var table = aliasRepo.catalog(new Person(null, null, 0));
        assertThat(table.rows().getFirst().get("person_key")).isEqualTo("1");

        var person = aliasRepo.retrieve(new Person("1", null, 0)).orElseThrow();
        assertThat(person.id()).isEqualTo("1");
    }

    // ── transact ─────────────────────────────────────────────────────────────

    @Test
    void transactCommitsAllOperationsOnSuccess() throws ShazoException {
        repo.transact(r -> {
            r.store(new Person("1", "Alice", 30));
            r.store(new Person("2", "Bob", 25));
            return null;
        });

        assertThat(repo.gather(new Person(null, null, 0))).hasSize(2);
    }

    @Test
    void transactRollsBackWhenTaskThrows() {
        assertThatThrownBy(() -> repo.transact(r -> {
            r.store(new Person("1", "Alice", 30));
            throw new ShazoException("simulated failure");
        })).isInstanceOf(ShazoException.class);

        assertThatThrownBy(() -> repo.find(new Person("1", null, 0)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void transactReturnsValueFromTask() throws ShazoException {
        repo.store(new Person("1", "Alice", 30));

        int count = repo.transact(r -> {
            var result = r.gather(new Person(null, null, 0));
            return result.size();
        });

        assertThat(count).isEqualTo(1);
    }
}
