-- 应用可先回滚并保留扩展列；有新正式平面结果时拒绝删除其类型事实。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM design_result_version WHERE result_line IS NOT NULL) THEN
        RAISE EXCEPTION '无法回滚结果类型列：存在已确认结果。请保留扩展表结构，先回滚应用，不要删除历史方案。';
    END IF;
END $$;
DROP INDEX uk_flat_result_selection;
ALTER TABLE design_result_version DROP CONSTRAINT ck_result_line;
ALTER TABLE design_result_version DROP COLUMN result_line;
