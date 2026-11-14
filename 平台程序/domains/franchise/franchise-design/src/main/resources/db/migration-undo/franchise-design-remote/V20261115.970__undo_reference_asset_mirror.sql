BEGIN;
LOCK TABLE fr_design_reference_mirror IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_design_reference_mirror) THEN
        RAISE EXCEPTION 'reference mappings exist; preserve mappings, remote assets and project references before rollback';
    END IF;
END $$;
DROP TABLE fr_design_reference_mirror;
COMMIT;
