-- 回滚 V20261009.506：恢复 design_result_version.flat_selection_id 的非空约束
--
-- 回滚有前提：「平面/立面拆分」上线后产生的纯立面最终版本没有平面选择（flat_selection_id 为空），
-- 恢复 NOT NULL 会让它们无处安放。这里不替你做主，也不悄悄删数据：存在这类版本就直接拒绝并说明条数，
-- 由负责人确认这些版本可以丢弃或另行迁移（例如先导出再删除）后再重试。
DO $$
DECLARE
  v_solo BIGINT;
  v_message TEXT;
BEGIN
  SELECT count(*) INTO v_solo FROM design_result_version WHERE flat_selection_id IS NULL;
  IF v_solo > 0 THEN
    v_message := '无法回滚 V20261009.506：仍有 ' || v_solo || ' 条纯立面最终版本（flat_selection_id 为空）。'
      || '请先确认这些版本可以丢弃或已导出，再执行 DELETE FROM design_result_version WHERE flat_selection_id IS NULL 后重试。';
    RAISE EXCEPTION '%', v_message;
  END IF;
  ALTER TABLE design_result_version ALTER COLUMN flat_selection_id SET NOT NULL;
END $$;
