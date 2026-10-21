-- 回滚 V20261016.601__case_home_featured_and_uploader.sql
-- 注意：undo 会丢弃上传人展示数据（uploader_name/uploader_company），重做迁移后需重新录入；
-- home_featured 运营标记同样不会恢复，重做后需重新标记。案例本体与版本数据不受影响。

DROP INDEX IF EXISTS idx_design_case_home_featured;

ALTER TABLE design_case
    DROP COLUMN IF EXISTS home_featured,
    DROP COLUMN IF EXISTS home_featured_at,
    DROP COLUMN IF EXISTS uploader_name,
    DROP COLUMN IF EXISTS uploader_company;
