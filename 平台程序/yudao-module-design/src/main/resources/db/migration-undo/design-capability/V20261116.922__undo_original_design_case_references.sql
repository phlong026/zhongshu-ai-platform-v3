-- Manual rollback only before the first published reference has been imported.
-- Never discard the immutable image identities or live project reference mappings.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM design_capability_case_image)
       OR EXISTS (SELECT 1 FROM design_capability_case_mirror) THEN
        RAISE EXCEPTION 'DESIGN_REFERENCE_ROLLBACK_REQUIRES_EMPTY_TABLES';
    END IF;
END $$;
DROP TABLE design_capability_case_mirror;
DROP TABLE design_capability_case_image;
