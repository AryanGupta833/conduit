CREATE TABLE IF NOT EXISTS execution_notifications (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(160) NOT NULL UNIQUE,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(300) NOT NULL,
    message TEXT,
    workflow_execution_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_execution_notification_created
    ON execution_notifications (created_at DESC);
