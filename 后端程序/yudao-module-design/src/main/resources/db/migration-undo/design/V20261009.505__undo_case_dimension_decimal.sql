-- 回滚 V20261009.505：面宽/进深退回整数列（四舍五入），删除尺度筛选索引
DROP INDEX IF EXISTS idx_design_case_version_dimension;

ALTER TABLE design_case_version
    ALTER COLUMN face_width TYPE INT USING ROUND(face_width),
    ALTER COLUMN depth TYPE INT USING ROUND(depth);
