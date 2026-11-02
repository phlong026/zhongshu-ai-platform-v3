-- V20261102.817：绩效提成核心四表（阶段 3，franchise-performance 域）
-- goal/task/task_archive/cycle；规则来源 docs/backend-parity/19-阶段3绩效提成经营（V2.1 两轮评审 PASS）。
-- 单轨定标（D-2）：只建任务机制+周期复核；auto 指标实时派生不落源表（PerfMetrics）。
-- 租户口径：DO @TenantIgnore + 显式 company_id；金额一律分（D-10）；period='YYYY-MM'。

CREATE TABLE fr_perf_goal (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, period CHAR(7) NOT NULL,
    sales_cents BIGINT NULL, collections_cents BIGINT NULL,
    effective_count INT NULL, customers_count INT NULL, nodes_count INT NULL,
    department_goals JSONB NULL, staff_department_goals JSONB NULL, legacy_staff JSONB NULL,
    version INT NOT NULL DEFAULT 1, history JSONB NULL,
    author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_goal PRIMARY KEY (id),
    CONSTRAINT uk_fr_perf_goal UNIQUE (company_id, period),
    CONSTRAINT ck_fr_perf_goal_period CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_fr_perf_goal_cents CHECK (
        (sales_cents IS NULL OR sales_cents >= 0) AND (collections_cents IS NULL OR collections_cents >= 0))
);

-- 绩效任务（manual+10 类 auto；type 合法集不含 financeReviews——§九）
CREATE TABLE fr_perf_task (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, period CHAR(7) NOT NULL,
    title VARCHAR(60) NOT NULL,
    assignee_user_id BIGINT NOT NULL, assignee_name VARCHAR(64) NOT NULL,
    assignee_dept VARCHAR(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    target_cents BIGINT NOT NULL,            -- 金额类=分；数量类=个（不乘 100，判定按原值）
    due DATE NOT NULL,
    manual_done JSONB NULL,
    history JSONB NULL,
    suggested BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_task PRIMARY KEY (id),
    CONSTRAINT ck_fr_perf_task_dept CHECK (assignee_dept IN ('business', 'engineering', 'finance')),
    CONSTRAINT ck_fr_perf_task_type CHECK (type IN ('manual',
        'auto:collectionsAmount', 'auto:contract', 'auto:completedNodes', 'auto:onTimeNodes',
        'auto:publications', 'auto:effectiveLeads', 'auto:qualifiedDeliveries',
        'auto:approvedDeliverables', 'auto:financeRegistrations')),
    CONSTRAINT ck_fr_perf_task_due CHECK (due >= DATE(period || '-01') AND due < (DATE(period || '-01') + INTERVAL '1 month')),
    CONSTRAINT ck_fr_perf_task_target CHECK (target_cents >= 0)
);
CREATE INDEX idx_fr_perf_task_period ON fr_perf_task (company_id, period, assignee_user_id);
CREATE INDEX idx_fr_perf_task_scope ON fr_perf_task (company_id, period, assignee_dept);

-- 归档快照（revision 序号递增；supersedes 链；任务机 sourceSignature 冻结）
CREATE TABLE fr_perf_task_archive (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, period CHAR(7) NOT NULL,
    archive_no INT NOT NULL, supersedes BIGINT NULL,
    tasks JSONB NOT NULL, source_signature VARCHAR(2048) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ARCHIVED', note VARCHAR(500) NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_task_archive PRIMARY KEY (id),
    CONSTRAINT ck_fr_perf_task_archive_status CHECK (status = 'ARCHIVED'),
    CONSTRAINT uk_fr_perf_task_archive UNIQUE (company_id, period, archive_no)
);
CREATE INDEX idx_fr_perf_task_archive_period ON fr_perf_task_archive (company_id, period);

-- 周期复核（人员×月；申诉裁决 adjustments 并入 PerfMetrics 聚合——D-3/P2-3）
CREATE TABLE fr_perf_cycle (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, period CHAR(7) NOT NULL, user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW',
    source_signature VARCHAR(2048) NULL,
    submission JSONB NULL,
    adjustments JSONB NULL,
    review JSONB NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_cycle PRIMARY KEY (id),
    CONSTRAINT ck_fr_perf_cycle_status CHECK (status IN ('PENDING_REVIEW', 'REVIEWED', 'RETURNED')),
    CONSTRAINT uk_fr_perf_cycle UNIQUE (company_id, period, user_id)
);
