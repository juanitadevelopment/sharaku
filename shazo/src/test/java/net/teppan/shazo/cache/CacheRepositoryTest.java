package net.teppan.shazo.cache;

import net.teppan.shazo.InMemoryRepository;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.ShazoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CacheRepository}.
 */
class CacheRepositoryTest {

    record Item(String id, String value) {}

    private InMemoryRepository<Item> backing;
    private CacheRepository<Item> cache;

    @BeforeEach
    void setUp() {
        backing = new InMemoryRepository<>(Item::id);
        cache = new CacheRepository<>(backing, Duration.ofSeconds(60), Item::id);
    }

    // ── retrieve — cache hit avoids delegate call ────────────────────────────

    @Test
    void retrieveHitsBackingRepositoryOnFirstCall() throws ShazoException {
        backing.store(new Item("1", "hello"));

        var result = cache.retrieve(new Item("1", null));

        assertThat(result).contains(new Item("1", "hello"));
        assertThat(backing.retrieveCallCount()).isEqualTo(1);
    }

    @Test
    void retrieveServesFromCacheOnSecondCall() throws ShazoException {
        backing.store(new Item("1", "hello"));
        cache.retrieve(new Item("1", null));
        cache.retrieve(new Item("1", null));

        assertThat(backing.retrieveCallCount()).isEqualTo(1);
    }

    @Test
    void retrieveReturnsEmptyWhenAbsent() throws ShazoException {
        assertThat(cache.retrieve(new Item("missing", null))).isEmpty();
    }

    // ── contains — returns true for cached entry ─────────────────────────────

    @Test
    void containsReturnsTrueForCachedEntry() throws ShazoException {
        backing.store(new Item("1", "hello"));
        cache.retrieve(new Item("1", null));

        assertTrue(cache.contains(new Item("1", null)));
    }

    @Test
    void containsDelegatesToBackingWhenNotCached() throws ShazoException {
        backing.store(new Item("2", "world"));
        assertTrue(cache.contains(new Item("2", null)));
    }

    // ── store — invalidates the cache entry ──────────────────────────────────

    @Test
    void storeInvalidatesCacheEntry() throws ShazoException {
        backing.store(new Item("1", "original"));
        cache.retrieve(new Item("1", null));

        cache.store(new Item("1", "updated"));
        var result = cache.retrieve(new Item("1", null));

        assertThat(result).contains(new Item("1", "updated"));
    }

    @Test
    void storeForwardsToBacking() throws ShazoException {
        cache.store(new Item("1", "hello"));
        assertThat(backing.size()).isEqualTo(1);
    }

    // ── delete — invalidates the cache entry ─────────────────────────────────

    @Test
    void deleteInvalidatesCacheEntry() throws ShazoException {
        var item = new Item("1", "hello");
        backing.store(item);
        cache.retrieve(new Item("1", null));

        cache.delete(item);

        assertFalse(backing.contains(new Item("1", null)));
        assertThat(cache.retrieve(new Item("1", null))).isEmpty();
    }

    // ── catalog — always delegates ────────────────────────────────────────────

    @Test
    void catalogAlwaysDelegatesToBacking() throws ShazoException {
        backing.store(new Item("1", "a"));
        backing.store(new Item("2", "b"));

        var all = cache.gather(new Item(null, null));

        assertThat(all).hasSize(2);
    }

    // ── invalidateAll ─────────────────────────────────────────────────────────

    @Test
    void invalidateAllClearsAllEntries() throws ShazoException {
        backing.store(new Item("1", "hello"));
        cache.retrieve(new Item("1", null));

        cache.invalidateAll();

        assertThat(cache.size()).isEqualTo(0);
        assertThat(backing.retrieveCallCount()).isEqualTo(1);
        cache.retrieve(new Item("1", null));
        assertThat(backing.retrieveCallCount()).isEqualTo(2);
    }

    // ── invalidate (single) ───────────────────────────────────────────────────

    @Test
    void invalidateSingleEntryLeavesOthersIntact() throws ShazoException {
        backing.store(new Item("1", "a"));
        backing.store(new Item("2", "b"));
        cache.retrieve(new Item("1", null));
        cache.retrieve(new Item("2", null));

        cache.invalidate(new Item("1", null));

        assertThat(cache.size()).isEqualTo(1);
    }

    // ── find ──────────────────────────────────────────────────────

    @Test
    void findThrowsNotFoundExceptionWhenAbsent() {
        assertThatThrownBy(() -> cache.find(new Item("missing", null)))
            .isInstanceOf(NotFoundException.class);
    }
}
