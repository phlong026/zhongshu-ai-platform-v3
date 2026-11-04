-- V20261104.708：离职交接待分配记录（M01-T04 / Codex R1 P1-3）
-- 紧急撤权在途对象不伪造已交接：逐已接入域落 OPEN 待分配行（可查询、可补交接）；
-- 正常交接/补交接完成后核销为 HANDED_OVER。in_flight = -1 表示扫描失败时未知（补交接按全量转移）。
CREATE TABLE pf_handover_pending
(
    id               BIGSERIAL PRIMARY KEY,
    org_id           BIGINT       NOT NULL,
    from_user_id     BIGINT       NOT NULL,
    to_user_id       BIGINT       NULL,
    domain           VARCHAR(64)  NOT NULL,
    in_flight        BIGINT       NOT NULL DEFAULT 0,
    status           VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    operator_user_id BIGINT       NOT NULL,
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    handed_over_at   TIMESTAMPTZ  NULL,
    CONSTRAINT ck_handover_pending_status CHECK (status IN ('OPEN', 'HANDED_OVER'))
);
-- 同一（组织，离职人，域）至多一条 OPEN 待分配（部分唯一索引；HANDED_OVER 历史可多条）
CREATE UNIQUE INDEX uk_handover_pending_open ON pf_handover_pending (org_id, from_user_id, domain) WHERE status = 'OPEN';
CREATE INDEX idx_handover_pending_org ON pf_handover_pending (org_id, status);
