CREATE TABLE spec_forms (
    project_id TEXT PRIMARY KEY REFERENCES spec_projects(id) ON DELETE CASCADE,
    config_json TEXT NOT NULL,
    version INTEGER NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE TABLE spec_form_participants (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id),
    name TEXT NOT NULL,
    secret_hash TEXT NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    UNIQUE(project_id, user_id)
);
CREATE TABLE spec_form_members (
    project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    role TEXT NOT NULL,
    PRIMARY KEY(project_id, user_id)
);
CREATE TABLE spec_form_rows (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
    creator_key TEXT NOT NULL,
    creator_name TEXT NOT NULL,
    values_json TEXT NOT NULL,
    version INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0,
    request_key TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(project_id, creator_key, request_key)
);
CREATE INDEX idx_form_rows ON spec_form_rows(project_id, deleted, created_at);
CREATE TABLE spec_form_events (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
    row_id TEXT,
    actor_name TEXT NOT NULL,
    action TEXT NOT NULL,
    before_json TEXT,
    after_json TEXT,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_form_events ON spec_form_events(project_id, row_id, created_at);
CREATE TABLE spec_form_comments (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
    row_id TEXT NOT NULL REFERENCES spec_form_rows(id) ON DELETE CASCADE,
    actor_name TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_form_comments ON spec_form_comments(row_id, created_at);
ALTER TABLE spec_items ADD COLUMN form_row_id TEXT;
CREATE INDEX idx_spec_item_row ON spec_items(form_row_id);
