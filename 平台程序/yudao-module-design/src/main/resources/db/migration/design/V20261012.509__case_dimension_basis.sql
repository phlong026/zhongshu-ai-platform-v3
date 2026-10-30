-- T26-07: add explicit provenance for dimensions without reinterpreting historical values.
ALTER TABLE design_case_version
    ADD COLUMN dimension_basis varchar(32) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE design_case_version
    ADD CONSTRAINT ck_case_version_dimension_basis
    CHECK (dimension_basis IN ('UNKNOWN', 'SITE', 'BUILDING_OUTLINE'));
COMMENT ON COLUMN design_case_version.dimension_basis IS
    'UNKNOWN=unverified historical meaning; SITE=site boundary; BUILDING_OUTLINE=building outer dimensions. Never infer from width/depth values.';
