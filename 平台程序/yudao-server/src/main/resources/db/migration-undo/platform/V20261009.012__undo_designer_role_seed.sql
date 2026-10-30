-- 回滚 V20261009.012：删除设计师角色及其菜单绑定（不动 system_user_role：已开通的设计师账号需在用户管理手工停用）
--
-- 种子绑定的 id 是 970000 + menu_id，菜单 9501/9502/9529/9530/9531 对应 979501/979502/979529/979530/979531。
-- 先前的区间 970000~970999 永远匹配不到这些行，回滚后绑定残留，再次执行种子时 ON CONFLICT (id) 会跳过它们，
-- 新建的设计师角色就没有任何菜单。这里按固定 id 清理，并一并清掉该角色在管理端手工追加的绑定。
DO $$
DECLARE
  v_role_id BIGINT;
BEGIN
  IF to_regclass('public.system_role') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;
  SELECT id INTO v_role_id FROM system_role WHERE code = 'designer' AND deleted = 0 LIMIT 1;
  DELETE FROM system_role_menu
   WHERE id IN (979501, 979502, 979529, 979530, 979531)
      OR (v_role_id IS NOT NULL AND role_id = v_role_id);
  UPDATE system_role SET deleted = 1, update_time = now() WHERE code = 'designer' AND deleted = 0;
END $$;
