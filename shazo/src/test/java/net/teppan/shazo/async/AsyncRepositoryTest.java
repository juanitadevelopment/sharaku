package net.teppan.shazo.async;

import net.teppan.shazo.InMemoryRepository;
import net.teppan.shazo.ShazoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AsyncRepository}.
 */
class AsyncRepositoryTest {

    record Note(String id, String text) {}

    private InMemoryRepository<Note> backing;
    private AsyncRepository<Note> async;

    @BeforeEach
    void setUp() {
        backing = new InMemoryRepository<>(Note::id);
        async   = new AsyncRepository<>(backing);
    }

    // ── contains ─────────────────────────────────────────────────────────────

    @Test
    void containsReturnsFalseWhenAbsent() throws Exception {
        var result = async.contains(new Note("x", null)).get();
        assertFalse(result);
    }

    @Test
    void containsReturnsTrueAfterStore() throws Exception {
        async.store(new Note("1", "hello")).get();
        assertTrue(async.contains(new Note("1", null)).get());
    }

    // ── store + retrieve ──────────────────────────────────────────────────────

    @Test
    void storeAndRetrieveRoundTrip() throws Exception {
        var note = new Note("1", "hello");
        async.store(note).get();

        var result = async.retrieve(new Note("1", null)).get();
        assertThat(result).contains(note);
    }

    @Test
    void retrieveReturnsEmptyWhenAbsent() throws Exception {
        var result = async.retrieve(new Note("missing", null)).get();
        assertThat(result).isEmpty();
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesEntity() throws Exception {
        var note = new Note("1", "to-delete");
        async.store(note).get();
        async.delete(note).get();

        assertFalse(backing.contains(new Note("1", null)));
    }

    // ── catalog ───────────────────────────────────────────────────────────────

    @Test
    void catalogReturnsAllEntities() throws Exception {
        async.store(new Note("1", "a")).get();
        async.store(new Note("2", "b")).get();

        var all = async.gather(new Note(null, null)).get();
        assertThat(all).hasSize(2);
    }

    // ── error propagation ─────────────────────────────────────────────────────

    @Test
    void shazoExceptionIsWrappedInCompletionException() {
        // FailingRepository throws ShazoException on every call
        var failing = new InMemoryRepository<Note>(Note::id) {
            @Override
            public boolean contains(Note query) throws ShazoException {
                throw new ShazoException("storage unavailable");
            }
        };
        var failAsync = new AsyncRepository<>(failing);

        var future = failAsync.contains(new Note("x", null));

        // CompletableFuture.get() wraps the exception in ExecutionException.
        // Because CompletableFuture unwraps a CompletionException when it
        // stores the failure, get() produces: ExecutionException -> ShazoException.
        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(ShazoException.class)
            .hasRootCauseMessage("storage unavailable");
    }

    // ── synchronous() accessor ────────────────────────────────────────────────

    @Test
    void synchronousReturnsDelegate() {
        assertThat(async.synchronous()).isSameAs(backing);
    }
}
