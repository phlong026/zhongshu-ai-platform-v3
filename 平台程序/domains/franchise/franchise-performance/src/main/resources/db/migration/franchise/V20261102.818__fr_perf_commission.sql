-- V20261102.818：回款提成两表（阶段 3，franchise-performance 域；D-6）
-- policy 硬锁：已有 release 后整行不可改（004）；无 release 修改=version+1（蓝本 collection-commission.js:77）。
-- release=本域留痕（payable_id 锚 finance 应付，经 PerformancePayablePort 幂等键 perf-commission:{projectId}:{stage}）；
-- 净实收=fr_eng_collection_term 约定 vs fr_eng_collection 收款−fr_fin_refund 已核实退款（reconcile 状态机承担停付）。

CREATE TABLE fr_perf_commission_policy (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    total_cents BIGINT NOT NULL,
    assignee_user_id BIGINT NOT NULL, assignee_name VARCHAR(64) NOT NULL,
    start_cents BIGINT NOT NULL, topping_cents BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1, history JSONB NULL,
    evidence_ref VARCHAR(1024) NOT NULL, note VARCHAR(500) NOT NULL,
    author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_commission_policy PRIMARY KEY (id),
    CONSTRAINT uk_fr_perf_commission_policy UNIQUE (company_id, project_id),
    CONSTRAINT ck_fr_perf_commission_policy_amounts CHECK (
        total_cents > 0 AND total_cents <= 10000000000
        AND start_cents >= 0 AND topping_cents >= 0 AND start_cents + topping_cents = total_cents)
);
CREATE INDEX idx_fr_perf_commission_policy_org ON fr_perf_commission_policy (company_id, update_time);

CREATE TABLE fr_perf_commission_release (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    stage VARCHAR(16) NOT NULL,
    payable_id BIGINT NULL,
    policy_version INT NOT NULL,
    receipt_signature VARCHAR(2048) NOT NULL,
    receipt_ids JSONB NOT NULL,
    reconciliations JSONB NULL,
    release_user_id BIGINT NOT NULL, release_at TIMESTAMPTZ NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_commission_release PRIMARY KEY (id),
    CONSTRAINT ck_fr_perf_commission_release_stage CHECK (stage IN ('START', 'TOPPING')),
    CONSTRAINT uk_fr_perf_commission_release UNIQUE (project_id, stage)
);
CREATE INDEX idx_fr_perf_commission_release_policy ON fr_perf_commission_release (company_id, policy_id);
