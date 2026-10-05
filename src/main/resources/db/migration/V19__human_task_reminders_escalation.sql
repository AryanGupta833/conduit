ALTER TABLE human_task_requests ADD COLUMN reminder_at TIMESTAMP;
ALTER TABLE human_task_requests ADD COLUMN reminder_sent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE human_task_requests ADD COLUMN escalation_at TIMESTAMP;
ALTER TABLE human_task_requests ADD COLUMN escalation_assignee VARCHAR(200);
ALTER TABLE human_task_requests ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_human_task_reminder_due ON human_task_requests(status, reminder_at) WHERE reminder_sent = FALSE;
CREATE INDEX idx_human_task_escalation_due ON human_task_requests(status, escalation_at) WHERE escalated = FALSE;
