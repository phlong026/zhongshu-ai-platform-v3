-- B company announcements. This is not A's user_message broadcast mechanism.
CREATE TABLE pf_announcement (
    id varchar(36) PRIMARY KEY,
    tenant_id bigint NOT NULL,
    title varchar(100) NOT NULL CHECK (length(btrim(title)) > 0),
    summary varchar(200) NOT NULL DEFAULT '',
    content text NOT NULL CHECK (length(btrim(content)) BETWEEN 1 AND 20000),
    visibility varchar(16) NOT NULL CHECK (visibility IN ('ALL','BRANDS')),
    pinned boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by bigint NOT NULL,
    updated_by bigint NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE TABLE pf_announcement_brand (
    tenant_id bigint NOT NULL,
    announcement_id varchar(36) NOT NULL,
    brand_id bigint NOT NULL,
    PRIMARY KEY (tenant_id,announcement_id,brand_id),
    FOREIGN KEY (tenant_id,announcement_id) REFERENCES pf_announcement(tenant_id,id),
    FOREIGN KEY (tenant_id,brand_id) REFERENCES pf_map_brand(tenant_id,id)
);
CREATE INDEX pf_announcement_listing ON pf_announcement(tenant_id,status,pinned DESC,published_at DESC,created_at DESC,id DESC);
CREATE INDEX pf_announcement_brand_scope ON pf_announcement_brand(tenant_id,brand_id,announcement_id);
CREATE TABLE pf_announcement_audit (
    id bigserial PRIMARY KEY,
    tenant_id bigint NOT NULL,
    announcement_id varchar(36) NOT NULL,
    actor_id bigint NOT NULL,
    post_id bigint,
    action varchar(16) NOT NULL CHECK (action IN ('CREATE','UPDATE','PUBLISH','WITHDRAW')),
    revision bigint NOT NULL,
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,announcement_id) REFERENCES pf_announcement(tenant_id,id),
    UNIQUE (tenant_id,announcement_id,revision)
);
