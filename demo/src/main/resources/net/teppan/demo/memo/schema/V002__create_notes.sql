CREATE TABLE notes (
    id          VARCHAR(36)  PRIMARY KEY,
    title       VARCHAR(500) NOT NULL,
    author_name VARCHAR(200),
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_notes_updated ON notes (updated_at DESC);

CREATE TABLE note_pages (
    note_id     VARCHAR(36) NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    page_number INT         NOT NULL,
    body        TEXT,
    PRIMARY KEY (note_id, page_number)
);
