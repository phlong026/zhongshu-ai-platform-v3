-- Independent budgets are not synthetic design projects; existing rows and amounts remain untouched.
CREATE TABLE budget_draft (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    name VARCHAR(100) NOT NULL DEFAULT '独立快速预算',
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    state JSONB NOT NULL DEFAULT '{"inputOverrides":{},"optionIds":[]}'::jsonb,
    imported_inputs JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_project_id BIGINT NULL,
    source_version_id BIGINT NULL,
    source_snapshot_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    create_key VARCHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_draft_create UNIQUE(user_id,create_key),
    CONSTRAINT ck_budget_draft_state CHECK (jsonb_typeof(state)='object' AND jsonb_typeof(imported_inputs)='object'),
    CONSTRAINT ck_budget_draft_source CHECK (source_version_id IS NULL OR source_project_id IS NOT NULL)
);
CREATE INDEX idx_budget_draft_owner ON budget_draft(user_id,id DESC);
ALTER TABLE budget_estimate ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE budget_estimate ADD COLUMN draft_id BIGINT NULL;
ALTER TABLE budget_estimate ADD CONSTRAINT ck_budget_estimate_origin CHECK (
    (project_id IS NOT NULL AND draft_id IS NULL)
    OR (project_id IS NULL AND draft_id IS NOT NULL AND result_version_id IS NULL AND model='ITEMIZED_V1')
);
CREATE INDEX idx_budget_estimate_draft ON budget_estimate(user_id,draft_id,id DESC);
