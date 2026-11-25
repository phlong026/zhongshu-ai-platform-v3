-- Preserve every existing stage ID and receipt/refund link. Metadata is added in place.
ALTER TABLE fr_sale_contract ADD COLUMN payment_plan_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE fr_sale_contract ADD COLUMN payment_plan_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
    CHECK (payment_plan_mode IN ('LEGACY','CUSTOM'));
ALTER TABLE fr_sale_contract DROP CONSTRAINT ck_fr_sale_contract_first;
ALTER TABLE fr_sale_contract ADD CONSTRAINT ck_fr_sale_contract_first
    CHECK (first_cents >= 0 AND first_cents <= total_cents AND total_cents > 0 AND total_cents <= 1000000000000);

ALTER TABLE fr_sale_payment_stage ALTER COLUMN stage TYPE VARCHAR(64);
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage
    CHECK (stage IN ('first','tail','SIGN_AFTER','BASE_DONE','CAP_DONE') OR stage ~ '^STAGE_[0-9]+$');
ALTER TABLE fr_sale_payment_stage
    ADD COLUMN label VARCHAR(64),
    ADD COLUMN sequence INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN ratio_bps INTEGER,
    ADD COLUMN trigger_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN trigger_node_index INTEGER,
    ADD COLUMN plan_version INTEGER NOT NULL DEFAULT 1;

UPDATE fr_sale_payment_stage SET
    label = CASE stage WHEN 'SIGN_AFTER' THEN '签约后' WHEN 'BASE_DONE' THEN '基础完成后'
        WHEN 'CAP_DONE' THEN '封顶后' WHEN 'first' THEN '首款' ELSE '尾款' END,
    sequence = CASE stage WHEN 'SIGN_AFTER' THEN 1 WHEN 'first' THEN 1 WHEN 'BASE_DONE' THEN 2
        WHEN 'CAP_DONE' THEN 3 ELSE 4 END,
    trigger_type = CASE WHEN stage IN ('SIGN_AFTER','first') THEN 'BEFORE_START'
        WHEN stage = 'BASE_DONE' THEN 'LEGACY_BASE_DONE' WHEN stage = 'CAP_DONE' THEN 'LEGACY_TOPPING'
        WHEN due_date IS NOT NULL THEN 'DATE' ELSE 'ON_FINISH' END,
    trigger_node_index = NULL;
-- Legacy contracts retain their existing milestone semantics; no project node index is invented.
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_metadata CHECK (
    sequence > 0 AND plan_version > 0 AND (ratio_bps IS NULL OR ratio_bps BETWEEN 1 AND 10000)
    AND trigger_type IN ('ON_SIGN','BEFORE_START','NODE_COMPLETED','ON_FINISH','DATE','MANUAL','LEGACY_BASE_DONE','LEGACY_TOPPING')
    AND (trigger_node_index IS NULL OR trigger_node_index BETWEEN 1 AND 100));
-- An old whole-stage receipt remains historical evidence after its plan is increased or refunded.
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_paid;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_paid CHECK (
    (paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL AND status<>'PAID')
    OR (paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL));

CREATE TABLE fr_sale_payment_plan_version (
    contract_id BIGINT NOT NULL REFERENCES fr_sale_contract(id),
    company_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    contract_revision BIGINT NOT NULL,
    total_cents BIGINT NOT NULL,
    stages JSONB NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contract_id, version)
);
INSERT INTO fr_sale_payment_plan_version(contract_id,company_id,version,contract_revision,total_cents,stages,reason,actor_user_id)
SELECT c.id,c.company_id,1,c.revision,c.total_cents,
    COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.sequence,s.id)
        FROM fr_sale_payment_stage s WHERE s.contract_id=c.id AND s.company_id=c.company_id AND NOT s.deleted),'[]'::jsonb),
    '存量付款计划原地映射',c.author_user_id
FROM fr_sale_contract c;
CREATE INDEX idx_sale_payment_plan_version_company ON fr_sale_payment_plan_version(company_id,contract_id);
