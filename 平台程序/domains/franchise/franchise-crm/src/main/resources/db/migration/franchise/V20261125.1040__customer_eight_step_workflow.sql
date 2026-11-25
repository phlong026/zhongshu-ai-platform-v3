-- R05: explicit customer workflow evidence. Legacy customer statuses do not imply completed steps.
CREATE TABLE fr_customer_workflow (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    company_id bigint NOT NULL,
    customer_id bigint NOT NULL REFERENCES fr_customer(id),
    template_version int NOT NULL DEFAULT 1 CHECK(template_version = 1),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED','LOST')),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,tenant_id,company_id)
);
CREATE UNIQUE INDEX fr_customer_workflow_one_active ON fr_customer_workflow(tenant_id,company_id,customer_id)
    WHERE status IN ('ACTIVE','PAUSED');
CREATE INDEX fr_customer_workflow_customer ON fr_customer_workflow(tenant_id,company_id,customer_id,created_at DESC);

CREATE TABLE fr_customer_workflow_step (
    workflow_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    company_id bigint NOT NULL,
    step_code varchar(32) NOT NULL CHECK(step_code IN ('INVITATION','INTRODUCTION','HOUSE_SELECTION','SITE_VISIT','MEASUREMENT','QUOTATION')),
    status varchar(16) NOT NULL CHECK(status IN ('DRAFT','COMPLETED')),
    revision bigint NOT NULL CHECK(revision > 0),
    occurred_on date,
    note varchar(4000) NOT NULL DEFAULT '',
    fields jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(fields)='object'),
    attachments jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(attachments)='array'),
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(workflow_id,step_code),
    FOREIGN KEY(workflow_id,tenant_id,company_id) REFERENCES fr_customer_workflow(id,tenant_id,company_id),
    CHECK((status='COMPLETED')=(completed_at IS NOT NULL))
);

CREATE TABLE fr_customer_workflow_event (
    id bigint PRIMARY KEY,
    workflow_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    company_id bigint NOT NULL,
    step_code varchar(32),
    action varchar(16) NOT NULL CHECK(action IN ('SAVE','COMPLETE','REOPEN','STATUS')),
    revision bigint NOT NULL,
    occurred_on date,
    note varchar(4000) NOT NULL DEFAULT '',
    fields jsonb NOT NULL DEFAULT '{}'::jsonb,
    attachments jsonb NOT NULL DEFAULT '[]'::jsonb,
    actor_user_id bigint NOT NULL,
    actor_post_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(workflow_id,tenant_id,company_id) REFERENCES fr_customer_workflow(id,tenant_id,company_id),
    UNIQUE(workflow_id,revision)
);
CREATE INDEX fr_customer_workflow_event_history ON fr_customer_workflow_event(tenant_id,workflow_id,revision DESC);
