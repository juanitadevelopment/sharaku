package net.teppan.demo.memo;

import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.file.FileRepository;
import net.teppan.shazo.jdbc.JdbcRepository;
import net.teppan.shazo.jdbc.SchemaManager;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link Repository}{@code <Note>}.
 * The same suite runs against both the JDBC (H2) and file-system backends.
 */
class NoteRepositoryTest {

    // ── Common contract ───────────────────────────────────────────────────────

    abstract static class Contract {

        Repository<Note> repo;

        abstract Repository<Note> createRepo() throws Exception;

        @BeforeEach
        void setUp() throws Exception {
            repo = createRepo();
        }

        @Test
        void storeAndRetrieve_withPages() throws ShazoException, NotFoundException {
            var note = Note.create("Java Notes", "Alice", List.of(
                new Note.Page(1, "First page body"),
                new Note.Page(2, "Second page body")));
            repo.store(note);

            var found = repo.find(Note.byId(note.id()));
            assertThat(found.id()).isEqualTo(note.id());
            assertThat(found.title()).isEqualTo("Java Notes");
            assertThat(found.authorName()).isEqualTo("Alice");
            assertThat(found.pages()).hasSize(2);
            assertThat(found.pages().get(0).pageNumber()).isEqualTo(1);
            assertThat(found.pages().get(0).body()).isEqualTo("First page body");
            assertThat(found.pages().get(1).pageNumber()).isEqualTo(2);
            assertThat(found.pages().get(1).body()).isEqualTo("Second page body");
        }

        @Test
        void storeAndRetrieve_noPages() throws ShazoException, NotFoundException {
            var note = Note.create("Empty Note", "Bob", List.of());
            repo.store(note);

            var found = repo.find(Note.byId(note.id()));
            assertThat(found.title()).isEqualTo("Empty Note");
            assertThat(found.pages()).isEmpty();
        }

        @Test
        void pageBody_multiline() throws ShazoException, NotFoundException {
            var body = "Line 1\nLine 2\nLine 3";
            var note = Note.create("Multiline", "Alice", List.of(new Note.Page(1, body)));
            repo.store(note);

            var found = repo.find(Note.byId(note.id()));
            assertThat(found.pages().get(0).body()).isEqualTo(body);
        }

        @Test
        void contains_trueAfterStore() throws ShazoException {
            var note = Note.create("Note", "Alice", List.of());
            assertThat(repo.contains(Note.byId(note.id()))).isFalse();
            repo.store(note);
            assertThat(repo.contains(Note.byId(note.id()))).isTrue();
        }

        @Test
        void delete_removesNote() throws ShazoException {
            var note = Note.create("To Delete", "Bob", List.of());
            repo.store(note);
            repo.delete(Note.byId(note.id()));
            assertThat(repo.contains(Note.byId(note.id()))).isFalse();
        }

        @Test
        void delete_nonExistent_isNoOp() throws ShazoException {
            repo.delete(Note.byId("no-such-id"));
        }

        @Test
        void retrieve_returnsEmptyWhenAbsent() throws ShazoException {
            assertThat(repo.retrieve(Note.byId("missing"))).isEmpty();
        }

        @Test
        void find_throwsWhenAbsent() {
            assertThatThrownBy(() -> repo.find(Note.byId("missing")))
                .isInstanceOf(NotFoundException.class);
        }

        @Test
        void catalogAll_returnsAllNotes() throws ShazoException {
            var a = Note.create("Alpha", "Alice", List.of());
            var b = Note.create("Beta",  "Bob",   List.of());
            repo.store(a);
            repo.store(b);

            var list = repo.gather(Note.all());
            assertThat(list).hasSize(2);
            assertThat(list).extracting(Note::id).containsExactlyInAnyOrder(a.id(), b.id());
        }

        @Test
        void catalogAll_emptyWhenNoNotes() throws ShazoException {
            assertThat(repo.gather(Note.all())).isEmpty();
        }

        @Test
        void catalogSearchByTitle() throws ShazoException {
            repo.store(Note.create("Java Notes", "Alice", List.of()));
            repo.store(Note.create("Python Guide", "Bob", List.of()));

            var results = repo.gather(Note.search("java", null));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Java Notes");
        }

        @Test
        void catalogSearchByTitle_caseInsensitive() throws ShazoException {
            repo.store(Note.create("Java Notes", "Alice", List.of()));

            assertThat(repo.gather(Note.search("JAVA", null))).hasSize(1);
        }

        @Test
        void catalogSearchByAuthor() throws ShazoException {
            repo.store(Note.create("Note 1", "Alice Andersen", List.of()));
            repo.store(Note.create("Note 2", "Bob Brown",     List.of()));

            var results = repo.gather(Note.search(null, "Alice"));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).authorName()).isEqualTo("Alice Andersen");
        }

        @Test
        void store_overwritesExisting_updatesPages() throws ShazoException, NotFoundException {
            var note    = Note.create("Original", "Alice",
                            List.of(new Note.Page(1, "Old page")));
            var updated = new Note(note.id(), "Updated", "Alice", note.updatedAt(),
                            List.of(new Note.Page(1, "New page"), new Note.Page(2, "Added")));
            repo.store(note);
            repo.store(updated);

            var found = repo.find(Note.byId(note.id()));
            assertThat(found.title()).isEqualTo("Updated");
            assertThat(found.pages()).hasSize(2);
            assertThat(found.pages().get(0).body()).isEqualTo("New page");
            assertThat(found.pages().get(1).body()).isEqualTo("Added");
        }

        @Test
        void gatherReturnsAllStored() throws ShazoException, InterruptedException {
            var first = Note.create("First", "Alice", List.of());
            repo.store(first);
            Thread.sleep(5);
            var second = Note.create("Second", "Bob", List.of());
            repo.store(second);

            // gather order follows the catalog/storage order (backend-specific).
            var list = repo.gather(Note.all());
            assertThat(list).extracting(Note::title)
                .containsExactlyInAnyOrder("First", "Second");
        }
    }

    // ── JDBC (H2) backend ─────────────────────────────────────────────────────

    @Nested
    class JdbcBackend extends Contract {

        private static final AtomicInteger DB_COUNTER = new AtomicInteger();

        @Override
        Repository<Note> createRepo() throws ShazoException {
            var ds = H2DataSources.inMemory("note_test_" + DB_COUNTER.incrementAndGet());
            SchemaManager.apply(ds, "net/teppan/demo/memo/schema/");
            return new JdbcRepository<>(ds, new JdbcNoteDescriber());
        }
    }

    // ── File-system backend ───────────────────────────────────────────────────

    @Nested
    class FileBackend extends Contract {

        @TempDir
        Path tempDir;

        @Override
        Repository<Note> createRepo() {
            return new FileRepository<>(tempDir, new FileNoteDescriber());
        }
    }
}
