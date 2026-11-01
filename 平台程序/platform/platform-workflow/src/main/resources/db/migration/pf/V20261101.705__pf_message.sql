-- V20261101.705：平台员工消息（基-2 消息中心，11 文档）
-- 业务域经 MessageSender 推送、按人收（user_id，人换岗位不丢消息）、已读持久化。
-- 同一发送域内业务键唯一：事件重放/并发重发返回既有行，不产生重复消息。
-- type/route 为受控白名单（与 MessageConstants 同源，11 文档 §五.2）；新增值先登记再以新迁移改 CHECK。

CREATE TABLE pf_message
(
    id            BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,               -- 收件人（按人收）
    org_id        BIGINT        NOT NULL,               -- 归属组织（过滤/审计维度，接口不按它过滤）
    sender_domain VARCHAR(32)   NOT NULL,               -- 发送方域（受控登记）
    type          VARCHAR(16)   NOT NULL,               -- BUSINESS/LEARNING/SYSTEM
    route         VARCHAR(32)   NOT NULL,               -- 前端跳转路由键（白名单）
    object_id     BIGINT        NULL,                   -- 业务对象 id（可空；后端域雪花 id）
    title         VARCHAR(100)  NOT NULL,
    description   VARCHAR(500)  NOT NULL,
    dedupe_key    VARCHAR(128)  NOT NULL,               -- 发送方拼的业务幂等键
    read          BOOL          NOT NULL DEFAULT FALSE, -- PG 非保留字，合法列名
    read_time     TIMESTAMPTZ   NULL,
    create_time   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_pf_message PRIMARY KEY (id),
    CONSTRAINT ck_pf_message_type CHECK (type IN ('BUSINESS', 'LEARNING', 'SYSTEM')),
    CONSTRAINT ck_pf_message_route CHECK (route IN ('customer', 'course', 'personal-performance',
                                                    'privacy', 'design-dispatch', 'design-progress')),
    -- 已读必有读时间，未读必无
    CONSTRAINT ck_pf_message_read CHECK ((read AND read_time IS NOT NULL) OR (NOT read AND read_time IS NULL))
);

-- 同一发送域内业务键唯一：事件重放/并发重发不产生重复消息
CREATE UNIQUE INDEX uk_pf_message_dedupe ON pf_message (sender_domain, dedupe_key);
CREATE INDEX idx_pf_message_unread ON pf_message (user_id) WHERE NOT read;   -- 未读数 COUNT
CREATE INDEX idx_pf_message_list ON pf_message (user_id, id DESC);           -- 游标分页（id 雪花单调递增）

COMMENT ON TABLE pf_message IS '平台员工消息：业务域经 MessageSender 推送、按人收、已读持久化';
COMMENT ON COLUMN pf_message.dedupe_key IS '发送方拼的业务幂等键（如 fr-fin:receipt-confirmed:123）；同 (sender_domain, dedupe_key) 重发返回既有行';
COMMENT ON COLUMN pf_message.route IS '前端跳转路由键（白名单见 docs/backend-parity/11 §五.2）；新增先登记';
