DO $$
BEGIN
    IF to_regclass('public.workflow_executions') IS NOT NULL THEN
        ALTER TABLE public.workflow_executions
            DROP CONSTRAINT IF EXISTS workflow_executions_status_check;
        ALTER TABLE public.workflow_executions
            ADD CONSTRAINT workflow_executions_status_check
            CHECK (status IN (
                'PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'PAUSED',
                'CANCELLED', 'WAITING_FOR_HUMAN'
            ));
    END IF;

    IF to_regclass('public.task_executions') IS NOT NULL THEN
        ALTER TABLE public.task_executions
            DROP CONSTRAINT IF EXISTS task_executions_status_check;
        ALTER TABLE public.task_executions
            ADD CONSTRAINT task_executions_status_check
            CHECK (status IN (
                'PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'RETRYING',
                'SKIPPED', 'TIMEOUT', 'QUEUED', 'WAITING_FOR_HUMAN'
            ));
    END IF;
END $$;
