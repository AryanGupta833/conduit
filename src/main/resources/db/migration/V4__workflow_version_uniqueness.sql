DO $$
BEGIN
    IF to_regclass('public.workflow_versions') IS NOT NULL THEN
        -- Refuse to silently renumber historical workflow versions. If earlier
        -- concurrent writes already created duplicates, deployment should stop
        -- for explicit data repair before this invariant is enforced.
        IF EXISTS (
            SELECT 1
            FROM public.workflow_versions
            GROUP BY workflow_id, version_number
            HAVING COUNT(*) > 1
        ) THEN
            RAISE EXCEPTION 'Duplicate workflow version numbers exist; repair workflow_versions before applying V4';
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'uk_workflow_version_number'
              AND conrelid = 'public.workflow_versions'::regclass
        ) THEN
            ALTER TABLE public.workflow_versions
                ADD CONSTRAINT uk_workflow_version_number
                UNIQUE (workflow_id, version_number);
        END IF;
    END IF;
END
$$;
