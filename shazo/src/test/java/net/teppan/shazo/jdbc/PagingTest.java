package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Gathered;
import net.teppan.shazo.Page;
import net.teppan.shazo.ShazoException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paged {@code gather}/{@code catalog} against H2, covering both execution
 * paths of §1.1 direction (a):
 *
 * <ul>
 *   <li>the zero-burden fallback — no {@code catalogPaged} on the describer;
 *       the repository caps the key fetch ({@code setMaxRows}) and skips the
 *       offset itself; and</li>
 *   <li>the opt-in pushdown — {@code catalogPaged} maps the window into the
 *       describer's own SQL, so the storage applies offset and limit.</li>
 * </ul>
 */
class PagingTest {

    record Person(String id, String name, int age) {}

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private DataSource freshDs() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:paging_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE person (
                    id   VARCHAR(50) PRIMARY KEY,
                    name VARCHAR(100),
                    age  INT
                )
                """);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }

    /** Seeds p01..pNN so ORDER BY id is the natural walking order. */
    private void seed(DataSource ds, int n) throws SQLException {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "MERGE INTO person (id, name, age) VALUES (?, ?, ?)")) {
            for (int i = 1; i <= n; i++) {
                ps.setString(1, String.format("p%02d", i));
                ps.setString(2, "Name" + i);
                ps.setInt(3, 20 + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private Describer.Builder<Person, SqlCommand> baseDescriber(AtomicInteger retrieves) {
        return Describer.<Person, SqlCommand>builder()
            .contains(p -> List.of(SqlCommand.of(
                "SELECT 1 FROM person WHERE id = ?", p.id())))
            .store(p -> List.of(SqlCommand.of(
                "MERGE INTO person (id, name, age) VALUES (?, ?, ?)",
                p.id(), p.name(), p.age())))
            .delete(p -> List.of(SqlCommand.of(
                "DELETE FROM person WHERE id = ?", p.id())))
            .retrieve(p -> {
                retrieves.incrementAndGet();
                return List.of(SqlCommand.of(
                    "SELECT id, name, age FROM person WHERE id = ?", p.id()));
            })
            .catalog(p -> List.of(SqlCommand.of(
                "SELECT id FROM person ORDER BY id")))
            .key(row -> new Person((String) row.get("ID"), null, 0))
            .infuser(result -> result.primary().first().map(row -> new Person(
                (String) row.get("ID"),
                (String) row.get("NAME"),
                ((Number) row.get("AGE")).intValue()
            )).orElseThrow());
    }

    // ── Fallback path (no catalogPaged) ──────────────────────────────────────

    @Test
    void fallbackWalksAllPagesInOrder() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var repo = new JdbcRepository<>(ds, baseDescriber(new AtomicInteger()).build());

        var collected = new ArrayList<Person>();
        var page = Page.of(10);
        Gathered<Person> slice;
        var hasMoreSeen = new ArrayList<Boolean>();
        do {
            slice = repo.gather(new Person(null, null, 0), page);
            slice.forEach(collected::add);
            hasMoreSeen.add(slice.hasMore());
            page = page.next();
        } while (slice.hasMore());

        assertThat(collected).hasSize(25);
        assertThat(collected.get(0).id()).isEqualTo("p01");
        assertThat(collected.get(24).id()).isEqualTo("p25");
        assertThat(hasMoreSeen).containsExactly(true, true, false);
    }

    @Test
    void fallbackHasNoMoreOnExactBoundary() throws Exception {
        var ds = freshDs();
        seed(ds, 20);
        var repo = new JdbcRepository<>(ds, baseDescriber(new AtomicInteger()).build());

        var second = repo.gather(new Person(null, null, 0), new Page(10, 10));
        assertThat(second.size()).isEqualTo(10);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void fallbackOffsetSkipsLeadingRows() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var repo = new JdbcRepository<>(ds, baseDescriber(new AtomicInteger()).build());

        var slice = repo.gather(new Person(null, null, 0), new Page(10, 5));
        assertThat(slice.items()).extracting(Person::id)
            .containsExactly("p11", "p12", "p13", "p14", "p15");
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void emptyResultIsEmptyWithoutHasMore() throws Exception {
        var ds = freshDs();   // no rows
        var repo = new JdbcRepository<>(ds, baseDescriber(new AtomicInteger()).build());

        var slice = repo.gather(new Person(null, null, 0), Page.of(10));
        assertThat(slice.isEmpty()).isTrue();
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void pagedGatherRetrievesOnlyThePageNotTheMatchSet() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var retrieves = new AtomicInteger();
        var repo = new JdbcRepository<>(ds, baseDescriber(retrieves).build());

        repo.gather(new Person(null, null, 0), Page.of(10));

        // The N+1 is capped at the page: 10 per-key retrieves, not 25.
        assertThat(retrieves.get()).isEqualTo(10);
    }

    @Test
    void pagedCatalogReturnsOnlyTheWindowRows() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var repo = new JdbcRepository<>(ds, baseDescriber(new AtomicInteger()).build());

        var page = repo.catalog(new Person(null, null, 0), new Page(5, 3));
        assertThat(page.size()).isEqualTo(3);
        assertThat(page.rows().get(0).get("ID")).isEqualTo("p06");
        assertThat(page.rows().get(2).get("ID")).isEqualTo("p08");
    }

    // ── Pushdown path (catalogPaged on the describer) ────────────────────────

    @Test
    void pushdownReceivesTheProbeWindowAndPagesCorrectly() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var seenPages = new ArrayList<Page>();
        var describer = baseDescriber(new AtomicInteger())
            .catalogPaged((q, p) -> {
                seenPages.add(p);
                return List.of(SqlCommand.of(
                    "SELECT id FROM person ORDER BY id LIMIT ? OFFSET ?",
                    p.limit(), p.offset()));
            })
            .build();
        var repo = new JdbcRepository<>(ds, describer);

        var slice = repo.gather(new Person(null, null, 0), new Page(10, 5));

        // The repository probes one row past the caller's window for hasMore.
        assertThat(seenPages).containsExactly(new Page(10, 6));
        assertThat(slice.items()).extracting(Person::id)
            .containsExactly("p11", "p12", "p13", "p14", "p15");
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void pushdownWalksAllPagesInOrder() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var describer = baseDescriber(new AtomicInteger())
            .catalogPaged((q, p) -> List.of(SqlCommand.of(
                "SELECT id FROM person ORDER BY id LIMIT ? OFFSET ?",
                p.limit(), p.offset())))
            .build();
        var repo = new JdbcRepository<>(ds, describer);

        var collected = new ArrayList<Person>();
        var page = Page.of(10);
        Gathered<Person> slice;
        do {
            slice = repo.gather(new Person(null, null, 0), page);
            slice.forEach(collected::add);
            page = page.next();
        } while (slice.hasMore());

        assertThat(collected).hasSize(25);
        assertThat(collected.getFirst().id()).isEqualTo("p01");
        assertThat(collected.getLast().id()).isEqualTo("p25");
    }

    @Test
    void pushdownHasNoMoreOnExactBoundary() throws Exception {
        var ds = freshDs();
        seed(ds, 20);
        var describer = baseDescriber(new AtomicInteger())
            .catalogPaged((q, p) -> List.of(SqlCommand.of(
                "SELECT id FROM person ORDER BY id LIMIT ? OFFSET ?",
                p.limit(), p.offset())))
            .build();
        var repo = new JdbcRepository<>(ds, describer);

        var second = repo.gather(new Person(null, null, 0), new Page(10, 10));
        assertThat(second.size()).isEqualTo(10);
        assertThat(second.hasMore()).isFalse();
    }

    // ── Unpaged behavior is unchanged ────────────────────────────────────────

    @Test
    void plainGatherStillReturnsEverything() throws Exception {
        var ds = freshDs();
        seed(ds, 25);
        var repo = new JdbcRepository<>(ds, baseDescriber(new AtomicInteger()).build());

        List<Person> all = repo.gather(new Person(null, null, 0));
        assertThat(all).hasSize(25);
    }
}
