CREATE TABLE spec_collections (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    used_files INTEGER NOT NULL DEFAULT 0,
    used_bytes BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_collection_project ON spec_collections(project_id);
CREATE TABLE spec_submissions (
    id TEXT PRIMARY KEY,
    collection_id TEXT NOT NULL REFERENCES spec_collections(id) ON DELETE CASCADE,
    request_key TEXT NOT NULL,
    filename TEXT NOT NULL,
    submitted_by TEXT NOT NULL,
    identifier TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(collection_id, request_key)
);
ALTER TABLE spec_items ADD COLUMN submitted_by TEXT;
ALTER TABLE spec_items ADD COLUMN submitted_identifier TEXT;
ALTER TABLE spec_items ADD COLUMN submission_id TEXT;
