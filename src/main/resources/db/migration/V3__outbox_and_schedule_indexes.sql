DO $$
BEGIN
    -- Flyway runs before Hibernate creates tables on a fresh installation.
    -- Existing installations receive these indexes/column in-place.
    IF to_regclass('public.outbox_events') IS NOT NULL THEN
        ALTER TABLE public.outbox_events ADD COLUMN IF NOT EXISTS last_error TEXT;
        CREATE INDEX IF NOT EXISTS idx_outbox_pending_id
            ON public.outbox_events (id)
            WHERE status = 'PENDING';
    END IF;

    IF to_regclass('public.workflows') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_workflows_active_cron
            ON public.workflows (id)
            WHERE active = TRUE AND cron_expression IS NOT NULL AND cron_expression <> '';
    END IF;
END
$$;
