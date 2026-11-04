-- V20261104.709：离职交接持久化审计（M01-T04 / Codex R2 P2-6）——交接清单落库可追溯：
-- 受影响域/数量、原责任人、接收人、操作者、模式；客户级明细由各域审计与 fr_customer revision 链承接。
CREATE TABLE pf_handover_audit
(
    id               BIGSERIAL PRIMARY KEY,
    org_id           BIGINT      NOT NULL,
    mode             VARCHAR(16) NOT NULL,
    from_user_id     BIGINT      NOT NULL,
    to_user_id       BIGINT      NULL,
    operator_user_id BIGINT      NOT NULL,
    revoked_post_ids TEXT        NOT NULL DEFAULT '',
    items_json       TEXT        NOT NULL,
    create_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_handover_audit_mode CHECK (mode IN ('NORMAL', 'EMERGENCY'))
);
CREATE INDEX idx_handover_audit_org ON pf_handover_audit (org_id, create_time);
