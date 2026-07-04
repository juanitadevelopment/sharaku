package net.teppan.backbone.blob;

import java.util.Objects;

/**
 * Caller-supplied metadata for a blob being stored via {@link BlobStore}.
 *
 * @param name      a human-readable name for the blob (e.g. an original file
 *                  name); never {@code null}
 * @param mediaType the blob's MIME type (e.g. {@code "application/pdf"});
 *                  never {@code null}
 * @see BlobStore
 * @see BlobRef
 */
public record BlobMeta(String name, String mediaType) {

    /** Compact constructor — rejects {@code null} fields. */
    public BlobMeta {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mediaType, "mediaType");
    }
}
