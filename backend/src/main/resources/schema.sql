CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT    NOT NULL,
    display_name  TEXT    NOT NULL,
    created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS media_files (
    id             TEXT    PRIMARY KEY,
    owner_id       INTEGER NOT NULL REFERENCES users(id),
    stored_name    TEXT    NOT NULL UNIQUE,
    original_name  TEXT    NOT NULL,
    role           TEXT    NOT NULL,
    media_type     TEXT    NOT NULL,
    mime_type      TEXT    NOT NULL,
    size_bytes     INTEGER NOT NULL,
    probe_json     TEXT,
    source_task_id TEXT,
    created_at     INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_media_owner   ON media_files(owner_id, role, created_at);
CREATE INDEX IF NOT EXISTS idx_media_expires ON media_files(expires_at);

CREATE TABLE IF NOT EXISTS tasks (
    id              TEXT    PRIMARY KEY,
    owner_id        INTEGER NOT NULL REFERENCES users(id),
    type            TEXT    NOT NULL,
    status          TEXT    NOT NULL,
    progress        INTEGER NOT NULL DEFAULT 0,
    stage           TEXT,
    message         TEXT,
    params_json     TEXT    NOT NULL,
    input_files     TEXT    NOT NULL,
    output_file     TEXT,
    result_json     TEXT,
    error_code      TEXT,
    attempt         INTEGER NOT NULL DEFAULT 1,
    origin          TEXT    NOT NULL DEFAULT 'PRO',
    idempotency_key TEXT,
    created_at      INTEGER NOT NULL,
    started_at      INTEGER,
    finished_at     INTEGER,
    updated_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_owner ON tasks(owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_idem ON tasks(owner_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ---------- AI mode (multi-agent conversations) ----------
CREATE TABLE IF NOT EXISTS ai_conversations (
    id             TEXT    PRIMARY KEY,
    owner_id       INTEGER NOT NULL REFERENCES users(id),
    title          TEXT    NOT NULL,
    state          TEXT    NOT NULL,
    plan_json      TEXT,
    plan_version   INTEGER NOT NULL DEFAULT 0,
    context_json   TEXT,
    prompt_version TEXT    NOT NULL,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_conv_owner ON ai_conversations(owner_id, updated_at);

CREATE TABLE IF NOT EXISTS ai_messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT    NOT NULL REFERENCES ai_conversations(id),
    role            TEXT    NOT NULL,
    agent           TEXT,
    content         TEXT    NOT NULL,
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_msg_conv ON ai_messages(conversation_id, id);

CREATE TABLE IF NOT EXISTS ai_events (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT    NOT NULL REFERENCES ai_conversations(id),
    type            TEXT    NOT NULL,
    payload_json    TEXT    NOT NULL,
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_evt_conv ON ai_events(conversation_id, seq);

-- ---------- V2 材料规范官 (spec desk) ----------
CREATE TABLE IF NOT EXISTS spec_projects (
    id                     TEXT    PRIMARY KEY,
    owner_id               INTEGER NOT NULL REFERENCES users(id),
    name                   TEXT    NOT NULL,
    notice_text            TEXT,
    spec_json              TEXT    NOT NULL,
    roster_json            TEXT,
    docx_template_file_id  TEXT,
    xlsx_template_file_id  TEXT,
    xlsx_header_row        INTEGER,
    version                INTEGER NOT NULL DEFAULT 1,
    created_at             INTEGER NOT NULL,
    updated_at             INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_spec_owner ON spec_projects(owner_id, updated_at);

CREATE TABLE IF NOT EXISTS spec_items (
    id             TEXT    PRIMARY KEY,
    project_id     TEXT    NOT NULL REFERENCES spec_projects(id),
    owner_id       INTEGER NOT NULL REFERENCES users(id),
    file_id        TEXT    NOT NULL,
    relative_path  TEXT    NOT NULL,
    person_key     TEXT,
    slot_key       TEXT,
    match_method   TEXT    NOT NULL,
    match_note     TEXT,
    status         TEXT    NOT NULL,
    check_json     TEXT,
    fixed_file_id  TEXT,
    fix_task_id    TEXT,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_spec_items_project ON spec_items(project_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_spec_items_file ON spec_items(project_id, file_id);
