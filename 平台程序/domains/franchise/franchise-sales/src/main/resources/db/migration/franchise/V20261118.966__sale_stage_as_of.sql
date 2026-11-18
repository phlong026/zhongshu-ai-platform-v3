-- Retain the stage terms in force before each effective contract change.
CREATE TABLE fr_sale_stage_before_change (
    company_id BIGINT NOT NULL,
    stage_id BIGINT NOT NULL REFERENCES fr_sale_payment_stage(id),
    contract_revision BIGINT NOT NULL,
    effective_on DATE NOT NULL,
    amount_cents BIGINT NOT NULL,
    due_date DATE,
    deleted BOOLEAN NOT NULL,
    PRIMARY KEY (company_id, stage_id, contract_revision)
);
CREATE INDEX idx_fr_sale_stage_before_change_as_of
    ON fr_sale_stage_before_change(company_id, stage_id, effective_on, contract_revision);
