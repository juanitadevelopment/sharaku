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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link Repository}{@code <Memo>}.
 *
 * <p>The same suite runs against both the JDBC (H2) and file-system backends
 * to verify identical behaviour — this is the point of the Shazo abstraction.
 */
class MemoRepositoryTest {

    // ── Common contract ───────────────────────────────────────────────────────

    abstract static class Contract {

        Repository<Memo> repo;

        abstract Repository<Memo> createRepo() throws Exception;

        @BeforeEach
        void setUp() throws Exception {
            repo = createRepo();
        }

        @Test
        void storeAndRetrieve() throws ShazoException, NotFoundException {
            var memo = Memo.create("Hello", "World body", "Alice");
            repo.store(memo);

            var found = repo.find(Memo.byId(memo.id()));
            assertThat(found.id()).isEqualTo(memo.id());
            assertThat(found.title()).isEqualTo("Hello");
            assertThat(found.body()).isEqualTo("World body");
            assertThat(found.authorName()).isEqualTo("Alice");
            assertThat(found.updatedAt()).isNotNull();
        }

        @Test
        void contains_trueAfterStore() throws ShazoException {
            var memo = Memo.create("X", "Y", "Z");
            assertThat(repo.contains(Memo.byId(memo.id()))).isFalse();
            repo.store(memo);
            assertThat(repo.contains(Memo.byId(memo.id()))).isTrue();
        }

        @Test
        void delete_removesEntity() throws ShazoException {
            var memo = Memo.create("Gone", "body", "Bob");
            repo.store(memo);
            repo.delete(Memo.byId(memo.id()));
            assertThat(repo.contains(Memo.byId(memo.id()))).isFalse();
        }

        @Test
        void delete_nonExistent_isNoOp() throws ShazoException {
            // should not throw
            repo.delete(Memo.byId("no-such-id"));
        }

        @Test
        void retrieve_returnsEmptyWhenAbsent() throws ShazoException {
            var result = repo.retrieve(Memo.byId("missing"));
            assertThat(result).isEmpty();
        }

        @Test
        void find_throwsWhenAbsent() {
            assertThatThrownBy(() -> repo.find(Memo.byId("missing")))
                .isInstanceOf(NotFoundException.class);
        }

        @Test
        void catalogAll_returnsAllMemos() throws ShazoException {
            var a = Memo.create("Alpha", "a body", "Alice");
            var b = Memo.create("Beta",  "b body", "Bob");
            repo.store(a);
            repo.store(b);

            var list = repo.gather(Memo.all());
            assertThat(list).hasSize(2);
            assertThat(list).extracting(Memo::id).containsExactlyInAnyOrder(a.id(), b.id());
        }

        @Test
        void catalogAll_emptyWhenNoMemos() throws ShazoException {
            assertThat(repo.gather(Memo.all())).isEmpty();
        }

        @Test
        void catalogSearchByTitle() throws ShazoException {
            repo.store(Memo.create("Java Tips", "body", "Alice"));
            repo.store(Memo.create("Python Guide", "body", "Bob"));

            var results = repo.gather(Memo.search("java", null));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Java Tips");
        }

        @Test
        void catalogSearchByTitle_caseInsensitive() throws ShazoException {
            repo.store(Memo.create("Java Tips", "body", "Alice"));

            var results = repo.gather(Memo.search("JAVA", null));
            assertThat(results).hasSize(1);
        }

        @Test
        void catalogSearchByAuthor() throws ShazoException {
            repo.store(Memo.create("Memo 1", "body", "Alice Andersen"));
            repo.store(Memo.create("Memo 2", "body", "Bob Brown"));

            var results = repo.gather(Memo.search(null, "Alice"));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).authorName()).isEqualTo("Alice Andersen");
        }

        @Test
        void catalogSearchBothFilters() throws ShazoException {
            repo.store(Memo.create("Java Tips", "body", "Alice"));
            repo.store(Memo.create("Java Guide", "body", "Bob"));
            repo.store(Memo.create("Python Tips", "body", "Alice"));

            var results = repo.gather(Memo.search("java", "alice"));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Java Tips");
        }

        @Test
        void store_overwritesExisting() throws ShazoException, NotFoundException {
            var memo    = Memo.create("Original", "body", "Alice");
            var updated = new Memo(memo.id(), "Updated", "new body", "Alice", memo.updatedAt());
            repo.store(memo);
            repo.store(updated);

            var found = repo.find(Memo.byId(memo.id()));
            assertThat(found.title()).isEqualTo("Updated");
            assertThat(found.body()).isEqualTo("new body");
        }

        @Test
        void gatherReturnsAllStored() throws ShazoException, InterruptedException {
            var counter = new AtomicInteger();
            // store two memos with a slight delay to ensure different updatedAt
            var first  = Memo.create("First",  "body", "Alice");
            repo.store(first);
            Thread.sleep(5);
            var second = Memo.create("Second", "body", "Bob");
            repo.store(second);

            // gather order follows the catalog/storage order, which is
            // backend-specific (JDBC sorts via SQL; the file store does not).
            var list = repo.gather(Memo.all());
            assertThat(list).extracting(Memo::title)
                .containsExactlyInAnyOrder("First", "Second");
            counter.incrementAndGet();
            assertThat(counter.get()).isEqualTo(1);
        }
    }

    // ── JDBC (H2) backend ─────────────────────────────────────────────────────

    @Nested
    class JdbcBackend extends Contract {

        private static final AtomicInteger DB_COUNTER = new AtomicInteger();

        @Override
        Repository<Memo> createRepo() throws ShazoException {
            var ds = H2DataSources.inMemory("memo_test_" + DB_COUNTER.incrementAndGet());
            SchemaManager.apply(ds, "net/teppan/demo/memo/schema/");
            return new JdbcRepository<>(ds, new JdbcMemoDescriber());
        }
    }

    // ── File-system backend ───────────────────────────────────────────────────

    @Nested
    class FileBackend extends Contract {

        @TempDir
        Path tempDir;

        @Override
        Repository<Memo> createRepo() {
            return new FileRepository<>(tempDir, new FileMemoDescriber());
        }
    }
}
