CREATE TABLE memos (
    id          VARCHAR(36)  PRIMARY KEY,
    title       VARCHAR(500) NOT NULL,
    body        TEXT,
    author_name VARCHAR(200),
    updated_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_memos_updated ON memos (updated_at DESC);
