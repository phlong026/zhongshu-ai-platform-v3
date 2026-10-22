-- V20261022.704：员工微信绑定（M1.6）
-- 不存明文手机号，只存绑定时手机号的 SHA-256；登录时与员工档案当前手机号比对，不一致即失效（老板改号后旧绑定自动作废）。
-- 单租户平台表，不带 tenant_id。

CREATE TABLE pf_wechat_binding
(
    id            BIGINT       NOT NULL,
    appid         VARCHAR(32)  NOT NULL,
    openid        VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    mobile_hash   CHAR(64)     NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    revoke_reason VARCHAR(32)  NULL,
    bound_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ  NULL,
    CONSTRAINT pk_pf_wechat_binding PRIMARY KEY (id),
    CONSTRAINT ck_pf_wechat_binding_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_pf_wechat_binding_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- 同一小程序下：一个微信只绑一名员工，一名员工只绑一个微信
CREATE UNIQUE INDEX uk_pf_wechat_binding_openid ON pf_wechat_binding (appid, openid) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uk_pf_wechat_binding_user ON pf_wechat_binding (appid, user_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE pf_wechat_binding IS '员工微信绑定；撤销保留历史';
COMMENT ON COLUMN pf_wechat_binding.mobile_hash IS '绑定时手机号的 SHA-256，不存明文';
COMMENT ON COLUMN pf_wechat_binding.revoke_reason IS 'MOBILE_CHANGED / USER_DISABLED / REBOUND';

-- 绑定审计：首次绑定、换微信重新绑定、因改号/停用/人员管理失效，均落库；按员工任职过的每个组织各记一行，
-- 以便各组织老板经组织范围过滤只看到本组织员工的记录。只追加，不修改。
CREATE TABLE pf_wechat_binding_event
(
    id                  BIGINT      NOT NULL,
    org_id              BIGINT      NOT NULL,
    user_id             BIGINT      NOT NULL,
    appid               VARCHAR(32) NOT NULL,
    binding_id          BIGINT      NOT NULL,
    previous_binding_id BIGINT      NULL,
    event_type          VARCHAR(16) NOT NULL,
    reason              VARCHAR(32) NULL,
    operator_user_id    BIGINT      NULL,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_pf_wechat_binding_event PRIMARY KEY (id),
    CONSTRAINT ck_pf_wechat_binding_event_type CHECK (event_type IN ('BOUND', 'REBOUND', 'REVOKED')),
    CONSTRAINT ck_pf_wechat_binding_event_reason CHECK ((event_type = 'REVOKED') = (reason IS NOT NULL))
);
CREATE INDEX idx_pf_wechat_binding_event_org_time ON pf_wechat_binding_event (org_id, occurred_at DESC);
CREATE INDEX idx_pf_wechat_binding_event_user ON pf_wechat_binding_event (user_id, occurred_at DESC);

COMMENT ON TABLE pf_wechat_binding_event IS '员工微信绑定审计：BOUND 首次绑定、REBOUND 换微信绑定（顶掉旧绑定）、REVOKED 失效';
COMMENT ON COLUMN pf_wechat_binding_event.org_id IS '员工任职过的组织；同一事件按组织各记一行，供老板按组织查看；无任职记录时为 0';
COMMENT ON COLUMN pf_wechat_binding_event.operator_user_id IS '人员管理中触发失效的操作人；登录时自动检测到的失效为空';
