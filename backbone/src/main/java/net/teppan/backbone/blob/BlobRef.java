package net.teppan.backbone.blob;

import java.time.Instant;

/**
 * Metadata for a blob stored by {@link BlobStore}, returned by
 * {@link BlobStore#store} and {@link BlobStore#metadata(long)}.
 *
 * <p>This record never carries the blob's content — use
 * {@link BlobStore#open(long)} to read it as a stream.
 *
 * @param id        the blob's identity, assigned by {@link BlobStore#store}
 * @param name      the caller-supplied name
 * @param mediaType the caller-supplied MIME type
 * @param size      the content length in bytes, measured while storing
 * @param sha256    the lowercase hex SHA-256 digest of the content, computed
 *                  while storing
 * @param createdAt when the blob was stored
 * @see BlobStore
 * @see BlobMeta
 */
public record BlobRef(long id, String name, String mediaType, long size,
                       String sha256, Instant createdAt) {
}
