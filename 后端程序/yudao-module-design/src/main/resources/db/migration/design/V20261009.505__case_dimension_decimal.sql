-- 案例面宽/进深支持一位小数（2026-10-09 运营需求 ③ 前置）
--
-- 背景：真实乡墅面宽/进深常见半米尺寸（如 12.6m）；INT 列会把管理端录入的 17.6 截断为 17。
-- 变更：face_width / depth INT → NUMERIC(4,1)，存量整数自动升位无精度损失；
--       新增 (face_width, depth) 索引服务小程序端 ±1m 尺度筛选。
ALTER TABLE design_case_version
    ALTER COLUMN face_width TYPE NUMERIC(4, 1),
    ALTER COLUMN depth TYPE NUMERIC(4, 1);

CREATE INDEX idx_design_case_version_dimension ON design_case_version (face_width, depth);
