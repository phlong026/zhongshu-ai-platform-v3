-- V20261022.702：平台组织与任职（M1.3）
-- 组织、团队复用 yudao 的 system_dept，本迁移只建扩展表；岗位即 yudao 角色（system_role.code）。
-- 单租户平台表：不带 tenant_id（实体标注 @TenantIgnore）。

-- 组织类型：由业务域注册，如加盟协同域的 headquarters / store
CREATE TABLE pf_org_type
(
    code        VARCHAR(32)  NOT NULL,
    domain      VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_pf_org_type PRIMARY KEY (code)
);
COMMENT ON TABLE pf_org_type IS '组织类型，由业务域迁移脚本注册';

-- 岗位模板：由业务域注册，规定岗位可用于哪些组织类型、所属条线、是否负责人及继承的专员岗位
CREATE TABLE pf_post_template
(
    role_code          VARCHAR(64)  NOT NULL,
    domain             VARCHAR(32)  NOT NULL,
    org_types          VARCHAR(255) NOT NULL,
    line               VARCHAR(32)  NULL,
    lead               BOOLEAN      NOT NULL DEFAULT FALSE,
    inherits_role_code VARCHAR(64)  NULL,
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_pf_post_template PRIMARY KEY (role_code)
);
COMMENT ON TABLE pf_post_template IS '岗位模板；role_code 即 system_role.code';
COMMENT ON COLUMN pf_post_template.org_types IS '允许的组织类型，逗号分隔，如 headquarters,store';
COMMENT ON COLUMN pf_post_template.inherits_role_code IS '负责人继承的专员岗位，如 sales-lead 继承 sales';

-- 组织单元：给 system_dept 标注组织或团队
CREATE TABLE pf_org_unit
(
    dept_id     BIGINT      NOT NULL,
    kind        VARCHAR(8)  NOT NULL,
    org_id      BIGINT      NOT NULL,
    org_type    VARCHAR(32) NULL,
    line        VARCHAR(32) NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_pf_org_unit PRIMARY KEY (dept_id),
    CONSTRAINT ck_pf_org_unit_kind CHECK (kind IN ('ORG', 'TEAM')),
    CONSTRAINT ck_pf_org_unit_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_pf_org_unit_org_type CHECK ((kind = 'ORG') = (org_type IS NOT NULL)),
    CONSTRAINT ck_pf_org_unit_org_self CHECK (kind <> 'ORG' OR org_id = dept_id),
    CONSTRAINT fk_pf_org_unit_org_type FOREIGN KEY (org_type) REFERENCES pf_org_type (code)
);
CREATE INDEX idx_pf_org_unit_org ON pf_org_unit (org_id);
COMMENT ON TABLE pf_org_unit IS 'system_dept 的扩展：ORG 为组织（总部/加盟商），TEAM 为组织内团队';
COMMENT ON COLUMN pf_org_unit.org_id IS '所属组织的 dept_id；ORG 行等于自身';

-- 任职：一人一岗一条；兼岗为多条；撤销保留历史
CREATE TABLE pf_staff_post
(
    id          BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    org_id      BIGINT      NOT NULL,
    role_code   VARCHAR(64) NOT NULL,
    team_id     BIGINT      NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by  BIGINT      NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by  BIGINT      NULL,
    revoked_at  TIMESTAMPTZ NULL,
    CONSTRAINT pk_pf_staff_post PRIMARY KEY (id),
    CONSTRAINT ck_pf_staff_post_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_pf_staff_post_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT fk_pf_staff_post_org FOREIGN KEY (org_id) REFERENCES pf_org_unit (dept_id),
    CONSTRAINT fk_pf_staff_post_role FOREIGN KEY (role_code) REFERENCES pf_post_template (role_code)
);
-- 同一人在同一组织同一岗位只能有一条有效任职
CREATE UNIQUE INDEX uk_pf_staff_post_active ON pf_staff_post (user_id, org_id, role_code) WHERE status = 'ACTIVE';
CREATE INDEX idx_pf_staff_post_org_role ON pf_staff_post (org_id, role_code) WHERE status = 'ACTIVE';
COMMENT ON TABLE pf_staff_post IS '员工任职；撤销后保留历史，会话按任职实时校验';
