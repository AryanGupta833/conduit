ALTER TABLE workflow_executions
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(36);

UPDATE workflow_executions
SET correlation_id = md5(random()::text || clock_timestamp()::text || id::text)
WHERE correlation_id IS NULL;

ALTER TABLE workflow_executions
    ALTER COLUMN correlation_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_workflow_executions_correlation_id
    ON workflow_executions(correlation_id);
