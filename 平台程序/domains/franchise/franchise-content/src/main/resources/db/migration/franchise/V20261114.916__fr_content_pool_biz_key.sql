-- B 内容任务固化公司积分池预扣键、提交意图和可恢复租约。
ALTER TABLE fr_content_task
    ADD COLUMN pool_biz_key VARCHAR(160),
    ADD COLUMN pool_reserved_units BIGINT NULL,
    ADD COLUMN pool_terminal_intent VARCHAR(16) NOT NULL DEFAULT 'SETTLE',
    ADD COLUMN dispatch_started_at TIMESTAMPTZ NULL,
    ADD COLUMN poll_lease_token VARCHAR(64) NULL,
    ADD COLUMN poll_lease_until TIMESTAMPTZ NULL,
    ADD COLUMN pool_reconcile_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN pool_reconcile_after TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN pool_reconcile_error VARCHAR(500) NULL,
    ADD COLUMN pool_reconcile_lease_token VARCHAR(64) NULL,
    ADD COLUMN pool_reconcile_lease_until TIMESTAMPTZ NULL,
    ADD COLUMN manual_verify_decision VARCHAR(16) NULL,
    ADD COLUMN manual_verify_actual_units BIGINT NULL,
    ADD COLUMN manual_verify_reason VARCHAR(500) NULL,
    ADD COLUMN manual_verify_evidence VARCHAR(1000) NULL,
    ADD COLUMN manual_verify_operator_user_id BIGINT NULL,
    ADD COLUMN manual_verify_operator_post_id BIGINT NULL,
    ADD COLUMN manual_verify_idempotency_key VARCHAR(80) NULL,
    ADD COLUMN manual_verified_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT ck_fct_pool_terminal_intent CHECK (pool_terminal_intent IN ('SETTLE','RELEASE')),
    ADD CONSTRAINT ck_fct_pool_reserved_units CHECK (
        pool_reserved_units IS NULL OR (pool_biz_key IS NOT NULL AND pool_reserved_units > 0)),
    ADD CONSTRAINT ck_fct_pool_reconcile_attempts CHECK (pool_reconcile_attempts >= 0),
    ADD CONSTRAINT ck_fct_manual_verify_decision CHECK (manual_verify_decision IN ('DELIVERED','NOT_DELIVERED')),
    ADD CONSTRAINT ck_fct_manual_verify_units CHECK (
        (manual_verify_decision IS NULL AND manual_verify_actual_units IS NULL)
        OR (manual_verify_decision = 'DELIVERED' AND manual_verify_actual_units > 0)
        OR (manual_verify_decision = 'NOT_DELIVERED' AND manual_verify_actual_units IS NULL));

CREATE INDEX idx_fct_pool_biz_key ON fr_content_task (pool_biz_key)
    WHERE pool_biz_key IS NOT NULL;

CREATE INDEX idx_fct_pool_reconcile ON fr_content_task (pool_reconcile_after, complete_time, id)
    WHERE pool_biz_key IS NOT NULL AND status IN ('SUCCEEDED','FAILED');

CREATE UNIQUE INDEX uk_fct_manual_verify_idem ON fr_content_task (company_id, manual_verify_idempotency_key)
    WHERE manual_verify_idempotency_key IS NOT NULL;

-- 人工账务核验权限只授予内容负责人与加盟商管理岗。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                         component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710504, '核验内容账务', 'franchise:content:verify', 3, 4, 710500, '', '', NULL,
        NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714190 + row_number() OVER (ORDER BY r.id), r.id, 710504, '1', '1', 1
FROM system_role r
WHERE r.tenant_id = 1 AND r.code IN ('sales-lead', 'boss');
