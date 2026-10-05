ALTER TABLE task_executions
    ADD COLUMN IF NOT EXISTS resolved_configuration_json TEXT;
