-- Application rollback can retain this additive column. Never erase confirmed basis data to force rollback.
-- Serialize the check and DDL so a concurrent confirmation cannot be lost between them.
DO $$ BEGIN
    LOCK TABLE design_case_version IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM design_case_version WHERE dimension_basis IN ('SITE', 'BUILDING_OUTLINE')) THEN
        RAISE EXCEPTION 'T26_07_DIMENSION_BASIS_ROLLBACK_REQUIRES_DATA_DISPOSITION';
    END IF;
    ALTER TABLE design_case_version DROP CONSTRAINT IF EXISTS ck_case_version_dimension_basis;
    ALTER TABLE design_case_version DROP COLUMN IF EXISTS dimension_basis;
END $$;
