CREATE TABLE fr_customer_payment_plan_draft (
    customer_id BIGINT PRIMARY KEY REFERENCES fr_customer(id),
    company_id BIGINT NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0),
    total_cents BIGINT NOT NULL CHECK(total_cents>0 AND total_cents<=1000000000000),
    stages JSONB NOT NULL CHECK(jsonb_typeof(stages)='array'),
    note VARCHAR(500) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX fr_customer_payment_plan_draft_company ON fr_customer_payment_plan_draft(company_id,customer_id);
CREATE TABLE fr_customer_payment_plan_draft_version (
    customer_id BIGINT NOT NULL REFERENCES fr_customer(id),
    company_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    total_cents BIGINT NOT NULL,
    stages JSONB NOT NULL,
    note VARCHAR(500) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(customer_id,revision)
);
CREATE INDEX fr_customer_payment_plan_draft_version_company ON fr_customer_payment_plan_draft_version(company_id,customer_id);
