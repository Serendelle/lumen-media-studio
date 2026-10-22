-- Personal history is separate from processing/audit records and the original/result files.
ALTER TABLE tasks ADD COLUMN history_deleted_at BIGINT;
CREATE INDEX idx_tasks_history ON tasks(owner_id, history_deleted_at, created_at DESC);
