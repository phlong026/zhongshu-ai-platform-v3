-- V20261102.812：内容创作获客核心六表（阶段 1.5，franchise-content 域）
-- script/task/draft/publication/provider_call/ai_usage；规则来源 docs/backend-parity/16-阶段1.5（V3.1 评审 PASS）。
-- 租户口径：DO @TenantIgnore + 显式 company_id（sales 惯例）；OrgScopeCustomizer 注册九表。
-- JSONB 写入走 JdbcTemplate CAST(? AS jsonb)（infra JdbcAuditPort / design DesignProjectService 先例）。

CREATE TABLE fr_content_script (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL, source_url VARCHAR(1024) NULL, platform VARCHAR(32) NULL,
    raw_text TEXT NOT NULL, optimized_text TEXT NULL, requirement VARCHAR(2000) NULL,
    persona_id BIGINT NULL, persona_snapshot JSONB NULL,
    engine VARCHAR(16) NULL, model VARCHAR(64) NULL, prompt_snapshot JSONB NULL,
    duration_seconds INT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_content_script PRIMARY KEY (id),
    CONSTRAINT ck_fr_content_script_source CHECK (source_type IN ('link','upload','manual')),
    CONSTRAINT ck_fr_content_script_engine CHECK (engine IS NULL OR engine IN ('deepseek','rules'))
);
CREATE INDEX idx_fr_content_script_org ON fr_content_script (company_id, author_user_id, create_time);

CREATE TABLE fr_content_task (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    input_snapshot JSONB NOT NULL, result_snapshot JSONB NULL, input_sha256 VARCHAR(64) NULL,
    provider VARCHAR(16) NULL, provider_task_id VARCHAR(128) NULL, result_url VARCHAR(1024) NULL,
    engine VARCHAR(32) NULL, model VARCHAR(64) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED', failed_reason VARCHAR(500) NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), complete_time TIMESTAMPTZ NULL,
    CONSTRAINT pk_fr_content_task PRIMARY KEY (id),
    CONSTRAINT ck_fr_content_task_kind CHECK (kind IN ('link_parse','asr','copy','digital','ai_video')),
    CONSTRAINT ck_fr_content_task_status CHECK (status IN
        ('QUEUED','SUBMITTING','RUNNING','ARCHIVING','SUCCEEDED','FAILED','SUBMISSION_UNCERTAIN')),
    CONSTRAINT ck_fr_content_task_provider CHECK (provider IS NULL OR provider IN
        ('diadi','dashscope','deepseek','hifly','metaso','fake'))
);
CREATE INDEX idx_fr_content_task_org ON fr_content_task (company_id, author_user_id, status, create_time);
-- 同素材共享转写缓存检索（AC4：同 sha256 二次提取命中缓存零外呼）
CREATE INDEX idx_fr_content_task_cache ON fr_content_task (company_id, kind, input_sha256)
    WHERE kind = 'asr' AND input_sha256 IS NOT NULL AND status = 'SUCCEEDED';

CREATE TABLE fr_content_draft (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL,
    task_id BIGINT NULL, kind VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL, script TEXT NULL, configuration JSONB NULL, cover_asset_id BIGINT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'draft',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_content_draft PRIMARY KEY (id),
    CONSTRAINT ck_fr_content_draft_kind CHECK (kind IN ('remix','digital','ai_video')),
    CONSTRAINT ck_fr_content_draft_status CHECK (status IN ('draft','saved'))
);
-- 一任务一件（AC9）：同一任务重复 save-work 返回既有作品
CREATE UNIQUE INDEX uk_fr_content_draft_task ON fr_content_draft (task_id)
    WHERE task_id IS NOT NULL AND deleted = FALSE;
CREATE INDEX idx_fr_content_draft_org ON fr_content_draft (company_id, author_user_id, update_time);

CREATE TABLE fr_content_publication (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL,
    publish_text TEXT NULL, tags VARCHAR(500) NULL, cover_asset_id BIGINT NULL,
    account_id VARCHAR(64) NOT NULL, platform VARCHAR(32) NOT NULL, scheduled_at TIMESTAMPTZ NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'publishing',
    published_url VARCHAR(1024) NULL, published_at TIMESTAMPTZ NULL,
    versions JSONB NULL, signature VARCHAR(128) NULL, failure_reason VARCHAR(500) NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_content_publication PRIMARY KEY (id),
    CONSTRAINT ck_fr_content_publication_status CHECK (status IN
        ('publishing','published','verify','failed','scheduled')),
    CONSTRAINT ck_fr_content_publication_schedule CHECK (
        (status <> 'scheduled' AND scheduled_at IS NULL) OR
        (status = 'scheduled' AND scheduled_at IS NOT NULL)),
    CONSTRAINT ck_fr_content_publication_published CHECK (
        (status = 'published' AND published_url IS NOT NULL AND published_at IS NOT NULL) OR
        (status <> 'published'))
);
-- 回执链接唯一（1_114_000_003）
CREATE UNIQUE INDEX uk_fr_content_publication_url ON fr_content_publication (published_url)
    WHERE published_url IS NOT NULL;
-- direct-v1 防重：同一作品同一账号非失败仅一条（§四）
CREATE UNIQUE INDEX uk_fr_content_publication_active ON fr_content_publication (work_id, account_id)
    WHERE status <> 'failed';
CREATE INDEX idx_fr_content_publication_org ON fr_content_publication (company_id, author_user_id, status);

CREATE TABLE fr_content_provider_call (
    id BIGINT NOT NULL, company_id BIGINT NULL, task_id BIGINT NULL,
    provider VARCHAR(32) NOT NULL, endpoint VARCHAR(128) NOT NULL,
    latency_ms INT NOT NULL, result_code VARCHAR(32) NOT NULL, units_billed INT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_content_provider_call PRIMARY KEY (id),
    CONSTRAINT ck_fr_content_provider_latency CHECK (latency_ms >= 0)
);
CREATE INDEX idx_fr_content_provider_call_org ON fr_content_provider_call (company_id, create_time);
CREATE INDEX idx_fr_content_provider_call_task ON fr_content_provider_call (task_id);

CREATE TABLE fr_content_ai_usage (
    id BIGINT NOT NULL, org_id BIGINT NOT NULL, service VARCHAR(32) NOT NULL,
    day DATE NOT NULL, count INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_fr_content_ai_usage PRIMARY KEY (id),
    CONSTRAINT uk_fr_content_ai_usage UNIQUE (org_id, service, day),
    CONSTRAINT ck_fr_content_ai_usage_count CHECK (count >= 0)
);
