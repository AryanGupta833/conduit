-- Existing installations are baselined at version 0 by Flyway. A fresh
-- database has no Hibernate-created tables yet, so the guard lets Hibernate
-- create the initial schema after Flyway has recorded this migration.
DO $$
BEGIN
    IF to_regclass('public.execution_logs') IS NOT NULL THEN
        ALTER TABLE public.execution_logs
            ALTER COLUMN message TYPE TEXT;
    END IF;
END
$$;
