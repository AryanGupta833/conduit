DO $$
BEGIN
    IF to_regclass('public.task_nodes') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM public.task_nodes
            WHERE workflow_version_id IS NOT NULL AND name IS NOT NULL
            GROUP BY workflow_version_id, name HAVING COUNT(*) > 1
        ) THEN
            RAISE EXCEPTION 'Duplicate task names exist within workflow versions; repair task_nodes before applying V5';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'uk_task_node_version_name'
              AND conrelid = 'public.task_nodes'::regclass
        ) THEN
            ALTER TABLE public.task_nodes
                ADD CONSTRAINT uk_task_node_version_name
                UNIQUE (workflow_version_id, name);
        END IF;
    END IF;

    IF to_regclass('public.dependencies') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM public.dependencies
            GROUP BY parent_task_id, child_task_id HAVING COUNT(*) > 1
        ) THEN
            RAISE EXCEPTION 'Duplicate workflow dependencies exist; repair dependencies before applying V5';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'uk_dependency_parent_child'
              AND conrelid = 'public.dependencies'::regclass
        ) THEN
            ALTER TABLE public.dependencies
                ADD CONSTRAINT uk_dependency_parent_child
                UNIQUE (parent_task_id, child_task_id);
        END IF;
    END IF;
END
$$;
