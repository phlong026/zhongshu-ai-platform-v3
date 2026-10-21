-- 修复设计师角色种子的租户归属（2026-10-10 联调实测发现）
--
-- 现象：V20261009.012 将角色行及其 role_menu 授权行插入 tenant_id=0，而本部署全部存量角色
--       均为 tenant_id=1（role_menu 用 tenant_id=0 的"惯例"只对绕过权限解析的超管成立——
--       普通角色必须与本租户一致才能通过 @PreAuthorize 权限解析）。
-- 后果：①角色分页接口按租户过滤后看不到「设计师」，用户管理选不到；
--       ②即便 API 强行绑定角色，设计师调用 design:case:* 一律 403。
-- 修复：按 code 幂等改写租户归属；已手工修过的库重放为无操作。
-- 守卫：与 V20261009.012 一致——底座 system_* 表不存在（全新测试库）时跳过。
DO $$
BEGIN
  IF to_regclass('public.system_role') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_role/system_role_menu 不存在，跳过设计师角色租户修正';
    RETURN;
  END IF;
  UPDATE system_role SET tenant_id = 1 WHERE code = 'designer' AND tenant_id = 0;
  UPDATE system_role_menu rm SET tenant_id = 1
  WHERE rm.role_id IN (SELECT id FROM system_role WHERE code = 'designer') AND rm.tenant_id = 0;
END $$;
