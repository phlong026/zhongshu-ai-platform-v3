-- T26-02：仅新确认的平面结果记录类型；不回填、推断或修改历史结果事实。
ALTER TABLE design_result_version ADD COLUMN result_line VARCHAR(16) NULL;
ALTER TABLE design_result_version ADD CONSTRAINT ck_result_line
    CHECK (result_line IS NULL OR result_line IN ('FLAT', 'ELEVATION'));
CREATE UNIQUE INDEX uk_flat_result_selection ON design_result_version(project_id, flat_selection_id)
    WHERE result_line = 'FLAT' AND deleted = FALSE;
