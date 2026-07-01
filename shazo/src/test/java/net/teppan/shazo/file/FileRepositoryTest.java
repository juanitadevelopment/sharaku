package net.teppan.shazo.file;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FileRepository}, focusing on the round trip plus the
 * path-traversal guard and atomic overwrite behaviour.
 */
class FileRepositoryTest {

    record Doc(String id, String body) {}

    @TempDir
    Path baseDir;

    private Repository<Doc> repo;

    private static String file(String id) { return id + ".doc"; }

    @BeforeEach
    void setUp() {
        Describer<Doc, FileCommand> describer = Describer.<Doc, FileCommand>builder()
            .contains(d -> List.of(new FileCommand.Read(file(d.id()))))
            .store   (d -> List.of(new FileCommand.Write(file(d.id()), d.id() + "\n" + d.body())))
            .delete  (d -> List.of(new FileCommand.Delete(file(d.id()))))
            .retrieve(d -> List.of(new FileCommand.Read(file(d.id()))))
            .catalog (d -> List.of(FileCommand.List.of("*.doc", FileRepositoryTest::parse)))
            .key(row -> new Doc((String) row.get("id"), null))
            .infuser(result -> result.primary().firstValue("_content", v -> (String) v)
                .map(FileRepositoryTest::decode).orElseThrow())
            .build();
        repo = new FileRepository<>(baseDir, describer);
    }

    private static Doc decode(String content) {
        int nl = content.indexOf('\n');
        return new Doc(content.substring(0, nl), content.substring(nl + 1));
    }

    private static Map<String, Object> parse(String content) {
        var d = decode(content);
        return Map.of("id", d.id(), "body", d.body());
    }

    @Test
    void storeAndRetrieveRoundTrip() throws ShazoException {
        repo.store(new Doc("a", "hello"));
        assertThat(repo.retrieve(new Doc("a", null))).contains(new Doc("a", "hello"));
    }

    @Test
    void overwriteReplacesContent() throws ShazoException {
        repo.store(new Doc("a", "v1"));
        repo.store(new Doc("a", "v2"));
        assertThat(repo.retrieve(new Doc("a", null))).contains(new Doc("a", "v2"));
        // Exactly one file should exist (no leftover temp files).
        try (var stream = Files.newDirectoryStream(baseDir)) {
            assertThat(stream).hasSize(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void retrieveAbsentReturnsEmpty() throws ShazoException {
        assertThat(repo.retrieve(new Doc("missing", null))).isEmpty();
    }

    @Test
    void deleteRemovesFile() throws ShazoException {
        repo.store(new Doc("a", "x"));
        repo.delete(new Doc("a", null));
        assertThat(repo.contains(new Doc("a", null))).isFalse();
    }

    @Test
    void catalogListsStoredDocs() throws ShazoException {
        repo.store(new Doc("a", "x"));
        repo.store(new Doc("b", "y"));
        assertThat(repo.gather(new Doc(null, null)))
            .containsExactlyInAnyOrder(new Doc("a", "x"), new Doc("b", "y"));
    }

    // ── Path-traversal guard ───────────────────────────────────────────────────

    @Test
    void storeRejectsParentTraversal() {
        assertThatThrownBy(() -> repo.store(new Doc("../escape", "x")))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("escapes base directory");
    }

    @Test
    void retrieveRejectsNestedTraversal() {
        assertThatThrownBy(() -> repo.retrieve(new Doc("../../etc/passwd", null)))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("escapes base directory");
    }

    @Test
    void traversalDoesNotCreateFileOutsideBase() throws ShazoException {
        var outside = baseDir.getParent().resolve("escape.doc");
        assertThatThrownBy(() -> repo.store(new Doc("../escape", "pwned")))
            .isInstanceOf(ShazoException.class);
        assertThat(Files.exists(outside)).isFalse();
    }
}
