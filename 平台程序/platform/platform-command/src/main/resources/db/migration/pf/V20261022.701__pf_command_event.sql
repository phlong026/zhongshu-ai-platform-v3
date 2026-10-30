-- V20261022.701：平台命令事件日志（M1.2）
-- 每个业务命令成功执行后在同一事务内追加一行；只追加，不更新、不删除。
-- 单租户平台表：不带 tenant_id（实体标注 @TenantIgnore）。

CREATE TABLE pf_command_event
(
    id               BIGINT       NOT NULL,
    domain           VARCHAR(32)  NOT NULL,
    aggregate_type   VARCHAR(64)  NOT NULL,
    aggregate_id     BIGINT       NOT NULL,
    revision         BIGINT       NOT NULL,
    command          VARCHAR(64)  NOT NULL,
    org_id           BIGINT       NOT NULL,
    actor_user_id    BIGINT       NOT NULL,
    actor_post_id    BIGINT       NULL,
    actor_role_code  VARCHAR(64)  NULL,
    payload          TEXT         NULL,
    payload_hash     CHAR(64)     NOT NULL,
    idempotency_key  VARCHAR(128) NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_pf_command_event PRIMARY KEY (id),
    CONSTRAINT ck_pf_command_event_revision_positive CHECK (revision > 0),
    CONSTRAINT uk_pf_command_event_aggregate_revision UNIQUE (domain, aggregate_type, aggregate_id, revision)
);

CREATE UNIQUE INDEX uk_pf_command_event_idempotency_key
    ON pf_command_event (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_pf_command_event_org_time ON pf_command_event (org_id, occurred_at);

COMMENT ON TABLE pf_command_event IS '平台命令事件日志：业务命令成功后同事务追加，只追加不修改';
COMMENT ON COLUMN pf_command_event.revision IS '命令执行后聚合的版本号；同一聚合同一版本只能有一条';
COMMENT ON COLUMN pf_command_event.payload IS '命令输入快照（JSON 文本），不得包含密钥、令牌等敏感明文';
COMMENT ON COLUMN pf_command_event.payload_hash IS 'payload 的 SHA-256，用于判断幂等重放的请求内容是否一致';
