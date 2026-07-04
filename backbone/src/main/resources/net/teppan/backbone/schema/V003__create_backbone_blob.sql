-- Blob store: opaque binary content (attachments, exported documents) that a
-- business row can reference. BlobStore.store(Connection, ...) writes on the
-- caller's own connection, so a row here commits or rolls back atomically
-- with whatever business row references it — the same pattern the
-- transactional outbox uses for events.
--
-- byte_size and sha256 start as placeholders and are corrected by a follow-up
-- UPDATE once the content stream has been fully read (see BlobStore.insert):
-- the true size and digest are only known after the whole stream has passed
-- through, which happens only once the driver finishes writing `content`.
CREATE TABLE backbone_blob (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(500) NOT NULL,
    media_type VARCHAR(255) NOT NULL,
    byte_size  BIGINT       NOT NULL,
    sha256     CHAR(64)     NOT NULL,
    content    BYTEA        NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
