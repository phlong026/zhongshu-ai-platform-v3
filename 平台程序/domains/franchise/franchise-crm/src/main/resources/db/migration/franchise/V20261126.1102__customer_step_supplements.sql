-- Additional material belongs to the customer and step, independently of the workflow draft/revision.
CREATE UNIQUE INDEX uk_fr_customer_supplement_scope ON fr_customer(id,company_id);

CREATE TABLE fr_customer_step_supplement (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    company_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    step_code varchar(32) NOT NULL CHECK (step_code IN (
        'INVITATION','INTRODUCTION','HOUSE_SELECTION','SITE_VISIT','MEASUREMENT','QUOTATION','CONTRACT','START')),
    note varchar(4000) NOT NULL DEFAULT '',
    attachments jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(attachments)='array' AND jsonb_array_length(attachments)<=20),
    actor_user_id bigint NOT NULL,
    actor_post_id bigint NOT NULL,
    actor_name varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (customer_id,company_id) REFERENCES fr_customer(id,company_id),
    CHECK (btrim(note)<>'' OR attachments<>'[]'::jsonb)
);

CREATE INDEX idx_fr_customer_step_supplement_history
    ON fr_customer_step_supplement(tenant_id,company_id,customer_id,step_code,created_at DESC,id DESC);
CREATE INDEX idx_fr_customer_step_supplement_assets
    ON fr_customer_step_supplement USING gin(attachments jsonb_path_ops);
