package net.teppan.shazo.composite;

import net.teppan.shazo.InMemoryRepository;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.ShazoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MixedRepository}.
 */
class MixedRepositoryTest {

    record Widget(String id, String label) {}

    private InMemoryRepository<Widget> primary;
    private InMemoryRepository<Widget> secondary;
    private MixedRepository<Widget> mixed;

    @BeforeEach
    void setUp() {
        primary   = new InMemoryRepository<>(Widget::id);
        secondary = new InMemoryRepository<>(Widget::id);
        mixed     = MixedRepository.of(primary, secondary);
    }

    // ── store — fans out to all repositories ─────────────────────────────────

    @Test
    void storeFansOutToBothRepositories() throws ShazoException {
        mixed.store(new Widget("1", "Gear"));

        assertThat(primary.size()).isEqualTo(1);
        assertThat(secondary.size()).isEqualTo(1);
    }

    @Test
    void storeCallCountIsOnePerDelegate() throws ShazoException {
        mixed.store(new Widget("1", "Gear"));

        assertThat(primary.storeCallCount()).isEqualTo(1);
        assertThat(secondary.storeCallCount()).isEqualTo(1);
    }

    // ── delete — fans out to all repositories ────────────────────────────────

    @Test
    void deleteFansOutToBothRepositories() throws ShazoException {
        var w = new Widget("1", "Gear");
        mixed.store(w);
        mixed.delete(w);

        assertFalse(primary.contains(new Widget("1", null)));
        assertFalse(secondary.contains(new Widget("1", null)));
    }

    // ── reads — served from primary only ─────────────────────────────────────

    @Test
    void containsQueriesPrimaryOnly() throws ShazoException {
        primary.store(new Widget("1", "Gear"));
        // secondary does NOT have this entry

        assertTrue(mixed.contains(new Widget("1", null)));
    }

    @Test
    void containsReturnsFalseWhenAbsentFromPrimary() throws ShazoException {
        secondary.store(new Widget("1", "Gear"));
        // only secondary has it — mixed should report absent

        assertFalse(mixed.contains(new Widget("1", null)));
    }

    @Test
    void retrieveReadFromPrimaryOnly() throws ShazoException {
        primary.store(new Widget("1", "FromPrimary"));
        secondary.store(new Widget("1", "FromSecondary"));

        var result = mixed.retrieve(new Widget("1", null));
        assertThat(result).contains(new Widget("1", "FromPrimary"));
    }

    @Test
    void retrieveReturnsEmptyWhenAbsentFromPrimary() throws ShazoException {
        secondary.store(new Widget("1", "Gear"));
        assertThat(mixed.retrieve(new Widget("1", null))).isEmpty();
    }

    @Test
    void catalogReadFromPrimaryOnly() throws ShazoException {
        primary.store(new Widget("1", "A"));
        primary.store(new Widget("2", "B"));
        secondary.store(new Widget("3", "C"));

        var all = mixed.gather(new Widget(null, null));
        assertThat(all).hasSize(2);
    }

    // ── find ─────────────────────────────────────────────────────

    @Test
    void findThrowsNotFoundExceptionWhenAbsentFromPrimary() {
        assertThatThrownBy(() -> mixed.find(new Widget("missing", null)))
            .isInstanceOf(NotFoundException.class);
    }

    // ── single-delegate construction ──────────────────────────────────────────

    @Test
    void mixedWithSingleDelegateWorksCorrectly() throws ShazoException {
        var single = MixedRepository.of(primary);
        single.store(new Widget("1", "Solo"));

        assertTrue(single.contains(new Widget("1", null)));
        assertThat(single.retrieve(new Widget("1", null)))
            .contains(new Widget("1", "Solo"));
    }
}
