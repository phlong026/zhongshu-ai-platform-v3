-- 服务资源 ID 仅用于 B 资产归属，无登录态、无旧 identity account 或 designgrant。
CREATE TABLE pf_org_service_account (
    id BIGINT PRIMARY KEY,
    org_id BIGINT NOT NULL UNIQUE REFERENCES pf_org_unit(dept_id),
    tenant_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'CLOSED')),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE TABLE pf_recharge_plan (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    price_fen BIGINT NOT NULL CHECK (price_fen > 0),
    base_points BIGINT NOT NULL CHECK (base_points > 0),
    bonus_points BIGINT NOT NULL DEFAULT 0 CHECK (bonus_points >= 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED' CHECK (status IN ('ACTIVE', 'DISABLED')),
    sort INTEGER NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pf_recharge_plan_active ON pf_recharge_plan(tenant_id, sort, id) WHERE status = 'ACTIVE';

-- 使用现有管理岗位权限关系派生，不把钱包读取/充值开放给普通员工。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
    component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (910100, '平台钱包', '', 1, 56, 0, '/platform-wallet', 'ep:wallet', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (910101, '读取组织钱包', 'platform:wallet:read', 3, 1, 910100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (910102, '充值组织钱包', 'platform:wallet:recharge', 3, 2, 910100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');
INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 911000 + row_number() OVER (ORDER BY src.tenant_id, src.role_id, dest.id),
       src.role_id, dest.id, '1', '1', src.tenant_id
FROM (SELECT DISTINCT rm.role_id, rm.tenant_id FROM system_role_menu rm
      JOIN system_menu m ON m.id = rm.menu_id
      JOIN system_role r ON r.id = rm.role_id AND r.tenant_id = rm.tenant_id
      WHERE m.permission = 'platform:org:manage' AND m.status = 0 AND r.status = 0
        AND COALESCE((to_jsonb(m)->>'deleted')::integer, 0) = 0
        AND COALESCE((to_jsonb(rm)->>'deleted')::integer, 0) = 0
        AND COALESCE((to_jsonb(r)->>'deleted')::integer, 0) = 0) src
CROSS JOIN (VALUES (910101), (910102)) dest(id);
