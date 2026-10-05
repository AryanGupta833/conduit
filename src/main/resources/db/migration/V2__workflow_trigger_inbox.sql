CREATE TABLE workflow_trigger_inbox (
    event_id UUID PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    execution_id BIGINT,
    created_at TIMESTAMP NOT NULL
);

DO $$
BEGIN
    -- On a fresh database, V1 is intentionally a no-op and Hibernate creates
    -- the base schema after Flyway. Existing installations already have this
    -- table and receive the FK here.
    IF to_regclass('public.workflow_executions') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM pg_constraint
           WHERE conname = 'fk_workflow_trigger_inbox_execution'
       ) THEN
        ALTER TABLE public.workflow_trigger_inbox
            ADD CONSTRAINT fk_workflow_trigger_inbox_execution
            FOREIGN KEY (execution_id) REFERENCES public.workflow_executions(id);
    END IF;
END
$$;

CREATE INDEX idx_workflow_trigger_inbox_workflow_id
    ON workflow_trigger_inbox (workflow_id);
