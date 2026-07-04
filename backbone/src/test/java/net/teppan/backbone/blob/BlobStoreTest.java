package net.teppan.backbone.blob;

import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobStoreTest {

    private DataSource ds;
    private BlobStore  store;

    @BeforeEach
    void setUp() {
        ds    = H2DataSources.inMemory("blob_" + System.nanoTime());
        store = new BlobStore(ds);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ── store/open round trip ────────────────────────────────────────────────────

    @Test
    void storesAndReopensContentExactly() throws Exception {
        byte[] payload = "hello, blob".getBytes();
        var ref = store.store(new ByteArrayInputStream(payload), new BlobMeta("greeting.txt", "text/plain"));

        assertThat(ref.name()).isEqualTo("greeting.txt");
        assertThat(ref.mediaType()).isEqualTo("text/plain");
        assertThat(ref.size()).isEqualTo(payload.length);
        assertThat(ref.sha256()).isEqualTo(sha256Hex(payload));

        try (var in = store.open(ref.id())) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void largerContentSpanningMultipleReadsRoundTrips() throws Exception {
        byte[] payload = new byte[64 * 1024 + 37];   // not a multiple of a typical buffer size
        new Random(42).nextBytes(payload);
        var ref = store.store(new ByteArrayInputStream(payload), new BlobMeta("blob.bin", "application/octet-stream"));

        assertThat(ref.size()).isEqualTo(payload.length);
        assertThat(ref.sha256()).isEqualTo(sha256Hex(payload));
        try (var in = store.open(ref.id())) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void emptyContentIsStoredAsZeroLength() throws Exception {
        var ref = store.store(new ByteArrayInputStream(new byte[0]), new BlobMeta("empty", "application/octet-stream"));
        assertThat(ref.size()).isZero();
        assertThat(ref.sha256()).isEqualTo(sha256Hex(new byte[0]));
        try (var in = store.open(ref.id())) {
            assertThat(in.readAllBytes()).isEmpty();
        }
    }

    // ── atomicity with the caller's transaction ──────────────────────────────────

    @Test
    void commitPersistsTheBlobWrittenOnTheSameConnection() throws Exception {
        byte[] payload = "committed".getBytes();
        long id;
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            id = store.store(conn, new ByteArrayInputStream(payload), new BlobMeta("a", "text/plain")).id();
            conn.commit();
        }
        try (var in = store.open(id)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void rollbackDiscardsTheBlobWrittenOnTheSameConnection() throws Exception {
        long id;
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            id = store.store(conn, new ByteArrayInputStream("never committed".getBytes()),
                new BlobMeta("a", "text/plain")).id();
            conn.rollback();
        }
        assertThatThrownBy(() -> store.open(id)).isInstanceOf(NotFoundException.class);
        assertThat(store.metadata(id)).isEmpty();
    }

    // ── metadata / missing ids ────────────────────────────────────────────────────

    @Test
    void metadataReturnsSameFieldsAsStoreWithoutContent() throws Exception {
        byte[] payload = "metadata check".getBytes();
        var ref = store.store(new ByteArrayInputStream(payload), new BlobMeta("m.txt", "text/plain"));

        var meta = store.metadata(ref.id()).orElseThrow();
        assertThat(meta).isEqualTo(ref);
    }

    @Test
    void metadataForMissingIdIsEmpty() throws Exception {
        assertThat(store.metadata(999_999L)).isEmpty();
    }

    @Test
    void openForMissingIdThrowsNotFound() {
        assertThatThrownBy(() -> store.open(999_999L)).isInstanceOf(NotFoundException.class);
    }

    // ── delete ────────────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheBlobAndReturnsFalseOnSecondCall() throws Exception {
        var ref = store.store(new ByteArrayInputStream("gone soon".getBytes()),
            new BlobMeta("d", "text/plain"));

        assertThat(store.delete(ref.id())).isTrue();
        assertThat(store.metadata(ref.id())).isEmpty();
        assertThat(store.delete(ref.id())).isFalse();
    }

    @Test
    void deleteForMissingIdReturnsFalse() throws Exception {
        assertThat(store.delete(999_999L)).isFalse();
    }

    // ── independent ids across stores ────────────────────────────────────────────

    @Test
    void eachStoreCallGetsADistinctId() throws Exception {
        var a = store.store(new ByteArrayInputStream("a".getBytes()), new BlobMeta("a", "text/plain"));
        var b = store.store(new ByteArrayInputStream("b".getBytes()), new BlobMeta("b", "text/plain"));
        assertThat(a.id()).isNotEqualTo(b.id());
    }
}
