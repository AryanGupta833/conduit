CREATE TABLE IF NOT EXISTS human_task_requests (
    id BIGSERIAL PRIMARY KEY,
    task_execution_id BIGINT NOT NULL UNIQUE REFERENCES task_executions(id),
    workflow_execution_id BIGINT NOT NULL REFERENCES workflow_executions(id),
    status VARCHAR(32) NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    assignee VARCHAR(200),
    request_payload TEXT,
    response_payload TEXT,
    created_by VARCHAR(200),
    completed_by VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    completed_at TIMESTAMP,
    timeout_policy VARCHAR(16) NOT NULL DEFAULT 'FAIL'
);
CREATE INDEX IF NOT EXISTS idx_human_task_status ON human_task_requests(status);
CREATE INDEX IF NOT EXISTS idx_human_task_assignee ON human_task_requests(assignee);
CREATE INDEX IF NOT EXISTS idx_human_task_workflow ON human_task_requests(workflow_execution_id);
CREATE INDEX IF NOT EXISTS idx_human_task_execution ON human_task_requests(task_execution_id);
CREATE INDEX IF NOT EXISTS idx_human_task_expires ON human_task_requests(expires_at);
