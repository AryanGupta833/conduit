ALTER TABLE workflows ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;

DO $$ BEGIN
    ALTER TABLE workflows ADD CONSTRAINT chk_workflows_priority CHECK (priority BETWEEN -10 AND 10);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_outbox_pending_priority ON outbox_events(status, priority DESC, created_at, id);
