-- B only. A remains the publication authority; this table stores employee links, never A user favorites.
CREATE TABLE fr_design_case_favorite (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    org_id bigint NOT NULL CHECK (org_id > 0),
    employee_user_id bigint NOT NULL CHECK (employee_user_id > 0),
    external_case_id bigint NOT NULL CHECK (external_case_id > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, org_id, employee_user_id, external_case_id)
);

CREATE INDEX fr_design_case_favorite_scope
    ON fr_design_case_favorite (tenant_id, org_id, employee_user_id, id DESC);
