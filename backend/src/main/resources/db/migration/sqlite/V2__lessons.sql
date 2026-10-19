-- V2: learning player lessons (SQLite).
CREATE TABLE IF NOT EXISTS lessons (
    id              TEXT    PRIMARY KEY,
    owner_id        INTEGER NOT NULL REFERENCES users(id),
    video_file_id   TEXT    NOT NULL,
    task_id         TEXT,
    title           TEXT    NOT NULL,
    data_json       TEXT    NOT NULL,
    transcript_json TEXT    NOT NULL,
    ocr_json        TEXT,
    meta_json       TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lessons_owner ON lessons(owner_id, updated_at);
