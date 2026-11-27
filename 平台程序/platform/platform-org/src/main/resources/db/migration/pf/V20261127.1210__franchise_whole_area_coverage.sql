ALTER TABLE pf_franchise_directory
    ADD COLUMN whole_area_coverage boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_franchise_whole_area_coverage CHECK (NOT whole_area_coverage OR record_type = 'COVERAGE');
