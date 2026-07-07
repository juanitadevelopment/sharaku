package net.teppan.backbone.blob;

import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.jdbc.SchemaManager;

import javax.sql.DataSource;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * A framework-owned store for opaque binary content — attachments, exported
 * documents, and other payloads too large or unstructured for a typed
 * {@link net.teppan.shazo.Repository} column.
 *
 * <h2>Atomic with business data</h2>
 * <p>{@link #store(Connection, InputStream, BlobMeta)} writes the blob using
 * the <em>caller's</em> connection, so it commits or rolls back together with
 * whatever business row references it (an attachment row, a document row) —
 * the same pattern {@code Outbox.write} uses for events. This is the reason
 * {@code BlobStore} lives in backbone rather than being an application-level
 * utility: a plain utility cannot give this guarantee, because it cannot see
 * the caller's transaction. Without a surrounding transaction, use
 * {@link #store(InputStream, BlobMeta)}.
 *
 * <h2>Content is opaque</h2>
 * <p>A blob is a name, a media type, and a byte stream — never a Java object
 * graph. There is deliberately no {@code Object}-accepting overload: typed
 * domain state belongs in a {@link net.teppan.shazo.Repository} via a
 * {@code Describer}, and reintroducing arbitrary Java serialization here would
 * reopen the gadget-chain risk that shazo's HTTP transport keeps closed with a
 * deserialization allowlist.
 *
 * <h2>Streaming</h2>
 * <p>{@link #store} writes {@code content} via {@code setBinaryStream} rather
 * than buffering it into a {@code byte[]} first, and {@link #open(long)}
 * returns a stream backed directly by the result set rather than
 * materializing the content in memory — both matter once blobs are megabytes,
 * not kilobytes. The size and SHA-256 digest reported in the resulting
 * {@link BlobRef} are measured as a side effect of that single streaming pass,
 * with no separate buffering pass to compute them.
 *
 * <h2>Deliberately out of scope (v1)</h2>
 * <p>No deduplication by digest, no retention/GC policy, and no external
 * backend (e.g. S3) — all DB-backed, one row per blob. These are candidates
 * for a later version once real usage shows they are needed.
 *
 * @see BlobMeta
 * @see BlobRef
 */
public final class BlobStore {

    private static final String SCHEMA_LOCATION = "net/teppan/backbone/schema/";
    private static final String TABLE = "backbone_blob";

    private final DataSource dataSource;

    /**
     * Creates a blob store backed by {@code dataSource}, applying the
     * {@code backbone_blob} schema migration if it has not already run.
     *
     * @param dataSource the database to store blobs in; never {@code null}
     */
    public BlobStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        try {
            SchemaManager.apply(dataSource, SCHEMA_LOCATION);
        } catch (ShazoException e) {
            throw new IllegalStateException("Failed to apply backbone blob schema", e);
        }
    }

    /**
     * Stores {@code content} using {@code txConn}, the connection of a
     * surrounding business transaction, so the blob commits or rolls back
     * atomically with whatever row references it.
     *
     * @param txConn  the transaction's connection; never {@code null}
     * @param content the blob's bytes; never {@code null}. Read to EOF but not
     *                closed — the caller owns it
     * @param meta    the blob's name and media type; never {@code null}
     * @return the stored blob's assigned id, measured size, and digest
     * @throws ShazoException if the write fails
     */
    public BlobRef store(Connection txConn, InputStream content, BlobMeta meta) throws ShazoException {
        Objects.requireNonNull(txConn, "txConn");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(meta, "meta");
        try {
            return insert(txConn, content, meta);
        } catch (SQLException e) {
            throw new ShazoException("Failed to store blob", e);
        }
    }

    /**
     * Stores {@code content} on its own connection, for a blob with no
     * surrounding business transaction to join.
     *
     * @param content the blob's bytes; never {@code null}. Read to EOF but not
     *                closed — the caller owns it
     * @param meta    the blob's name and media type; never {@code null}
     * @return the stored blob's assigned id, measured size, and digest
     * @throws ShazoException if the write fails
     */
    public BlobRef store(InputStream content, BlobMeta meta) throws ShazoException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(meta, "meta");
        try (var conn = dataSource.getConnection()) {
            return insert(conn, content, meta);
        } catch (SQLException e) {
            throw new ShazoException("Failed to store blob", e);
        }
    }

    /**
     * Opens the blob's content as a stream backed directly by the database —
     * the content is never fully materialized in memory. The connection
     * backing this read is held open until the returned stream is closed, so
     * callers must close it (try-with-resources).
     *
     * @param id the blob's id
     * @return a stream over the blob's content, positioned at the start
     * @throws NotFoundException if no blob with this id exists
     * @throws ShazoException    if the read fails
     */
    public InputStream open(long id) throws ShazoException {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = dataSource.getConnection();
            ps = conn.prepareStatement("SELECT content FROM " + TABLE + " WHERE id = ?");
            ps.setLong(1, id);
            rs = ps.executeQuery();
            if (!rs.next()) {
                closeQuietly(rs, ps, conn);
                throw new NotFoundException("blob id=" + id);
            }
            return new BlobContentStream(rs.getBinaryStream(1), rs, ps, conn);
        } catch (SQLException e) {
            closeQuietly(rs, ps, conn);
            throw new ShazoException("Failed to open blob id=" + id, e);
        }
    }

    /**
     * Reads a blob's metadata without its content.
     *
     * @param id the blob's id
     * @return the blob's metadata, or empty if no blob with this id exists
     * @throws ShazoException if the read fails
     */
    public Optional<BlobRef> metadata(long id) throws ShazoException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT name, media_type, byte_size, sha256, created_at"
                 + " FROM " + TABLE + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new BlobRef(id, rs.getString(1), rs.getString(2),
                    rs.getLong(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
            }
        } catch (SQLException e) {
            throw new ShazoException("Failed to read blob metadata id=" + id, e);
        }
    }

    /**
     * Permanently deletes a blob.
     *
     * @param id the blob's id
     * @return {@code true} if a blob was deleted; {@code false} if no blob
     *         with this id existed
     * @throws ShazoException if the delete fails
     */
    public boolean delete(long id) throws ShazoException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ShazoException("Failed to delete blob id=" + id, e);
        }
    }

    // ── Insert (shared by both store overloads) ──────────────────────────────────

    /**
     * Inserts a placeholder row (size/digest unknown yet) with {@code content}
     * bound via {@code setBinaryStream}, then — once {@code executeUpdate} has
     * forced the driver to read {@code content} to EOF through the digesting
     * wrapper — corrects {@code byte_size}/{@code sha256} with a follow-up
     * {@code UPDATE} on the same connection. Both statements are on {@code conn},
     * so they are part of whatever transaction (or none) {@code conn} is in.
     */
    private BlobRef insert(Connection conn, InputStream content, BlobMeta meta) throws SQLException {
        var tracked = new DigestingInputStream(content);
        // Truncated to microseconds so the BlobRef returned here is byte-for-byte
        // equal to what a later metadata(id) read produces: both H2's and
        // PostgreSQL's default TIMESTAMP column store 6 fractional digits, but
        // Instant.now() can carry true nanosecond precision on some JVMs (observed
        // on Linux; not on macOS, where the mismatch never showed up locally).
        // Matching the DB's precision up front — rather than only in the test
        // that happens to compare the two — keeps the equality BlobRef's javadoc
        // implies true for every caller, not just this one assertion.
        var createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long id;
        try (var ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + " (name, media_type, byte_size, sha256, content, created_at)"
                + " VALUES (?, ?, 0, '', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, meta.name());
            ps.setString(2, meta.mediaType());
            ps.setBinaryStream(3, tracked);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned for blob insert");
                }
                id = keys.getLong(1);
            }
        }
        // MessageDigest.digest() finalizes and resets the digest, so it must be
        // read exactly once; capture it here rather than calling sha256Hex() again
        // below (a second call would silently return the empty-content digest).
        long   size   = tracked.size();
        String sha256 = tracked.sha256Hex();
        try (var ps = conn.prepareStatement(
                "UPDATE " + TABLE + " SET byte_size = ?, sha256 = ? WHERE id = ?")) {
            ps.setLong(1, size);
            ps.setString(2, sha256);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
        return new BlobRef(id, meta.name(), meta.mediaType(), size, sha256, createdAt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void closeQuietly(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs != null) rs.close(); } catch (SQLException ignored) { /* best-effort */ }
        try { if (ps != null) ps.close(); } catch (SQLException ignored) { /* best-effort */ }
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { /* best-effort */ }
    }

    /** A stream over a blob's content that closes its backing JDBC resources on {@link #close()}. */
    private static final class BlobContentStream extends FilterInputStream {
        private final ResultSet rs;
        private final PreparedStatement ps;
        private final Connection conn;

        BlobContentStream(InputStream in, ResultSet rs, PreparedStatement ps, Connection conn) {
            super(in);
            this.rs = rs;
            this.ps = ps;
            this.conn = conn;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                closeQuietly(rs, ps, conn);
            }
        }
    }

    /**
     * Wraps an {@link InputStream}, computing its SHA-256 digest and byte count
     * as it is read. Passed directly to {@code setBinaryStream}, so the driver's
     * own read of the content — needed to write it — is what drives the
     * measurement; there is no separate pass over the bytes.
     */
    private static final class DigestingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long   size;
        private String cachedHex;   // MessageDigest.digest() resets on call; compute at most once

        DigestingInputStream(InputStream in) {
            super(in);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 must be available on every JVM", e);
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                digest.update((byte) b);
                size++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                digest.update(b, off, n);
                size += n;
            }
            return n;
        }

        long size() {
            return size;
        }

        String sha256Hex() {
            if (cachedHex == null) {
                cachedHex = HexFormat.of().formatHex(digest.digest());
            }
            return cachedHex;
        }
    }
}
