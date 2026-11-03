-- V20261102.819：经营视图两表（阶段 3，franchise-performance 域；D-7）
-- reconciliation=月末对账（按月覆盖写，boss/finance）；operating_snapshot=经营快照
-- （只允许过去月 008；source_signature 内嵌 report；变化 version+1 supersedes——蓝本 operating-report 同构）。

CREATE TABLE fr_perf_reconciliation (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, period CHAR(7) NOT NULL,
    income_cents BIGINT NOT NULL, paid_cents BIGINT NOT NULL, net_cents BIGINT NOT NULL,
    receipt_count INT NOT NULL, payment_count INT NOT NULL,
    by_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_reconciliation PRIMARY KEY (id),
    CONSTRAINT uk_fr_perf_reconciliation UNIQUE (company_id, period),
    CONSTRAINT ck_fr_perf_reconciliation_period CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$')
);

CREATE TABLE fr_perf_operating_snapshot (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, period CHAR(7) NOT NULL,
    version INT NOT NULL, supersedes BIGINT NULL,
    report JSONB NOT NULL, as_of DATE NOT NULL, note VARCHAR(500) NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'FINAL',
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_perf_operating_snapshot PRIMARY KEY (id),
    CONSTRAINT ck_fr_perf_operating_snapshot_status CHECK (status = 'FINAL'),
    CONSTRAINT ck_fr_perf_operating_snapshot_period CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT uk_fr_perf_operating_snapshot UNIQUE (company_id, period, version)
);
CREATE INDEX idx_fr_perf_operating_snapshot_period ON fr_perf_operating_snapshot (company_id, period);
