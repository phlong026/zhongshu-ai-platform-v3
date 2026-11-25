DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_fin_project_accounting) THEN
        RAISE EXCEPTION 'Project accounting confirmations exist; preserve the history before rollback';
    END IF;
END $$;
DROP TABLE fr_fin_project_accounting;
DROP FUNCTION fr_project_accounting_immutable();
