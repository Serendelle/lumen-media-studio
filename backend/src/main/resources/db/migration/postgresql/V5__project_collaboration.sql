-- Reuse spec_projects: personal material collection and team collaboration share one project identity.
ALTER TABLE spec_projects ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE spec_projects ADD COLUMN team_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE spec_projects ADD COLUMN publication_status TEXT NOT NULL DEFAULT 'DRAFT';
CREATE TABLE project_members (
 project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 user_id BIGINT NOT NULL REFERENCES users(id),
 role TEXT NOT NULL CHECK(role IN ('EDITOR','REVIEWER','VIEWER')),
 joined_at BIGINT NOT NULL,
 PRIMARY KEY(project_id,user_id)
);
CREATE INDEX idx_project_members_user ON project_members(user_id,project_id);
CREATE TABLE project_documents (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 provider TEXT NOT NULL, title TEXT NOT NULL, document_type TEXT NOT NULL,
 external_url TEXT NOT NULL, visibility TEXT NOT NULL CHECK(visibility IN ('ALL','SELECTED')),
 version BIGINT NOT NULL DEFAULT 1, created_by BIGINT NOT NULL REFERENCES users(id),
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, unlinked BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_project_documents_project ON project_documents(project_id,created_at);
CREATE TABLE project_document_members (
 document_id TEXT NOT NULL REFERENCES project_documents(id) ON DELETE CASCADE,
 user_id BIGINT NOT NULL REFERENCES users(id), PRIMARY KEY(document_id,user_id)
);
CREATE TABLE project_assets (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 document_id TEXT REFERENCES project_documents(id) ON DELETE CASCADE,
 archived BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL
);
CREATE TABLE project_file_versions (
 id TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES project_assets(id) ON DELETE CASCADE,
 project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 document_id TEXT REFERENCES project_documents(id) ON DELETE CASCADE,
 file_id TEXT NOT NULL REFERENCES media_files(id), version_number BIGINT NOT NULL,
 uploaded_by BIGINT NOT NULL REFERENCES users(id), created_at BIGINT NOT NULL,
 sha256 TEXT NOT NULL, review_status TEXT NOT NULL DEFAULT 'PENDING',
 review_note TEXT NOT NULL DEFAULT '', reviewed_by BIGINT REFERENCES users(id), reviewed_at BIGINT,
 UNIQUE(asset_id,version_number)
);
CREATE INDEX idx_project_versions_project ON project_file_versions(project_id,created_at);
CREATE TABLE project_bundle_sources (
 output_version_id TEXT NOT NULL REFERENCES project_file_versions(id) ON DELETE CASCADE,
 source_version_id TEXT NOT NULL REFERENCES project_file_versions(id) ON DELETE CASCADE,
 PRIMARY KEY(output_version_id,source_version_id)
);
CREATE TABLE project_document_versions (
 id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES project_documents(id) ON DELETE CASCADE,
 file_version_id TEXT NOT NULL UNIQUE REFERENCES project_file_versions(id) ON DELETE CASCADE,
 version_number BIGINT NOT NULL, imported_by BIGINT NOT NULL REFERENCES users(id),
 imported_at BIGINT NOT NULL, sha256 TEXT NOT NULL, UNIQUE(document_id,version_number)
);
CREATE TABLE project_activities (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 actor_id BIGINT NOT NULL REFERENCES users(id), actor_name TEXT NOT NULL,
 action TEXT NOT NULL, document_id TEXT REFERENCES project_documents(id) ON DELETE CASCADE,
 created_at BIGINT NOT NULL
);
CREATE INDEX idx_project_activities_project ON project_activities(project_id,created_at);
CREATE TABLE project_todos (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 title TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN', assignee_id BIGINT REFERENCES users(id),
 version BIGINT NOT NULL DEFAULT 1, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE project_tasks (
 task_id TEXT PRIMARY KEY REFERENCES tasks(id), project_id TEXT NOT NULL REFERENCES spec_projects(id) ON DELETE CASCADE,
 file_version_id TEXT REFERENCES project_file_versions(id) ON DELETE CASCADE,
 output_version_id TEXT REFERENCES project_file_versions(id) ON DELETE SET NULL,
 created_by BIGINT NOT NULL REFERENCES users(id), last_status TEXT NOT NULL, last_progress INTEGER NOT NULL DEFAULT -1,
 created_at BIGINT NOT NULL, request_key TEXT NOT NULL, output_error TEXT, UNIQUE(project_id,request_key)
);
