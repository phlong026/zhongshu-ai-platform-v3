-- 设计师角色种子：户型案例上传专用（2026-10-09 运营需求 ②）
--
-- 用途：设计师后台账号绑定本角色后，可在「户型库管理」新增公司案例、编辑参数、
--       上传图纸（上传走 design:case:update）；不含上下架权限——设计师保存草稿，
--       由运营审核上架，保证入库案例质量。
-- 授予菜单：9501 户型库目录、9502 户型库管理页面、9529 查询、9530 新增、9531 编辑。
-- 幂等：角色按 code=designer 复用；role_menu 以 970000+menu_id 固定 id ON CONFLICT 跳过；
--       授予关系与 V009 惯例一致（仅预绑定菜单，用户绑定在「用户管理」人工操作）。
DO $$
DECLARE
  v_role_id BIGINT;
BEGIN
  IF to_regclass('public.system_role') IS NULL OR to_regclass('public.system_menu') IS NULL
     OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_role/system_menu/system_role_menu 不存在，跳过设计师角色种子';
    RETURN;
  END IF;

  SELECT id INTO v_role_id FROM system_role WHERE code = 'designer' AND deleted = 0 LIMIT 1;
  IF v_role_id IS NULL THEN
    -- system_role.id 无自增默认（yudao 应用层雪花 id），种子必须显式赋 id；
    -- 取小整数段 MAX(id)+1（存量种子角色为 1/2 小 id，避免与业务雪花 id 碰撞）
    SELECT COALESCE(MAX(id), 0) + 1 INTO v_role_id FROM system_role WHERE id < 900000;
    INSERT INTO system_role (id, name, code, sort, data_scope, data_scope_dept_ids, status, type, remark,
                             creator, create_time, updater, update_time, deleted, tenant_id)
    VALUES (v_role_id, '设计师', 'designer', 10, 1, '', 0, 2,
            '户型案例上传专用：可见户型库管理，可新增/编辑案例与上传图纸，不含上下架',
            '', now(), '', now(), 0, 0);
  END IF;

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 970000 + m.id, v_role_id, m.id, '', now(), '', now(), 0, 0
  FROM system_menu m
  WHERE m.id IN (9501, 9502, 9529, 9530, 9531)
  ON CONFLICT (id) DO NOTHING;
END $$;
