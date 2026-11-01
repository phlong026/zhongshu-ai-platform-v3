-- V20261102.101：account 增加 external_key 稳定业务键（B2 板块 A / P-5 门店服务账号）
-- 唯一写方=identity AccountProvisionAdapter；普通 UNIQUE（PG 唯一约束允许多 NULL，与 ON CONFLICT (external_key) 推断匹配）。
-- 服务账号 external_key 形如 fr-service:{orgId}；存量 C 端账号该列为 NULL，不受影响。

ALTER TABLE account ADD COLUMN external_key VARCHAR(64) NULL;

ALTER TABLE account ADD CONSTRAINT uk_account_external_key UNIQUE (external_key);
