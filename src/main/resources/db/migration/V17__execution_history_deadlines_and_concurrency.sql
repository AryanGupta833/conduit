ALTER TABLE workflows
    ADD COLUMN IF NOT EXISTS max_concurrent_executions INTEGER;

DO $$ BEGIN
    ALTER TABLE workflows ADD CONSTRAINT chk_workflows_concurrency_limit
        CHECK (max_concurrent_executions IS NULL OR max_concurrent_executions BETWEEN 1 AND 1000);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE workflow_executions
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_workflow_executions_deadline
    ON workflow_executions(status, deadline_at)
    WHERE deadline_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS execution_events (
    id BIGSERIAL PRIMARY KEY,
    workflow_execution_id BIGINT NOT NULL REFERENCES workflow_executions(id) ON DELETE CASCADE,
    task_execution_id BIGINT,
    type VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    detail VARCHAR(2000)
);

CREATE INDEX IF NOT EXISTS idx_execution_events_execution_time
    ON execution_events(workflow_execution_id, occurred_at, id);
