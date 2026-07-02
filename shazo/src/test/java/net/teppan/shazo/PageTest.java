package net.teppan.shazo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Page}, {@link Gathered}, and the correct-but-unbounded
 * paged defaults on the {@link Repository} interface.
 */
class PageTest {

    @Test
    void ofStartsAtOffsetZero() {
        assertThat(Page.of(50)).isEqualTo(new Page(0, 50));
    }

    @Test
    void nextAdvancesByLimit() {
        assertThat(Page.of(10).next()).isEqualTo(new Page(10, 10));
        assertThat(new Page(30, 10).next()).isEqualTo(new Page(40, 10));
    }

    @Test
    void rejectsNegativeOffsetAndNonPositiveLimit() {
        assertThatThrownBy(() -> new Page(-1, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Page(0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gatheredIsImmutableAndIterable() {
        var mutable = new java.util.ArrayList<>(List.of("a", "b"));
        var g = new Gathered<>(mutable, true);
        mutable.add("c");                              // must not leak in

        assertThat(g.items()).containsExactly("a", "b");
        assertThat(g).containsExactly("a", "b");       // Iterable
        assertThat(g.size()).isEqualTo(2);
        assertThat(g.isEmpty()).isFalse();
        assertThat(g.hasMore()).isTrue();
    }

    // ── Repository default methods (interface-level fallback) ────────────────

    /** A minimal Repository that only knows how to gather/catalog everything. */
    private static final class FixedRepository implements Repository<String> {
        private final List<String> all;
        FixedRepository(List<String> all) { this.all = all; }

        @Override public boolean contains(String q) { return all.contains(q); }
        @Override public void store(String e)  { throw new UnsupportedOperationException(); }
        @Override public void delete(String e) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> retrieve(String q) { return Optional.empty(); }
        @Override public String find(String q) { throw new UnsupportedOperationException(); }
        @Override public List<String> gather(String q) { return all; }
        @Override public RawResult catalog(String q) {
            return RawResult.of(all.stream()
                .map(v -> Map.<String, Object>of("v", v))
                .toList());
        }
    }

    @Test
    void defaultPagedGatherSlicesAndReportsHasMore() throws Exception {
        var repo = new FixedRepository(List.of("a", "b", "c", "d", "e"));

        var first = repo.gather("q", Page.of(2));
        assertThat(first.items()).containsExactly("a", "b");
        assertThat(first.hasMore()).isTrue();

        var last = repo.gather("q", new Page(4, 2));
        assertThat(last.items()).containsExactly("e");
        assertThat(last.hasMore()).isFalse();

        var past = repo.gather("q", new Page(99, 2));
        assertThat(past.isEmpty()).isTrue();
        assertThat(past.hasMore()).isFalse();
    }

    @Test
    void defaultPagedGatherHasNoMoreOnExactBoundary() throws Exception {
        var repo = new FixedRepository(List.of("a", "b", "c", "d"));
        var second = repo.gather("q", new Page(2, 2));
        assertThat(second.items()).containsExactly("c", "d");
        assertThat(second.hasMore()).isFalse();       // full page, but nothing beyond
    }

    @Test
    void defaultPagedCatalogSlicesRows() throws Exception {
        var repo = new FixedRepository(List.of("a", "b", "c"));
        var page = repo.catalog("q", new Page(1, 5));
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.rows().get(0).get("v")).isEqualTo("b");
        assertThat(page.rows().get(1).get("v")).isEqualTo("c");
    }
}
