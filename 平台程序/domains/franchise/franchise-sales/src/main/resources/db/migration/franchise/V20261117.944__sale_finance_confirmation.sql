CREATE TABLE fr_sale_collection_declaration(
 id BIGINT PRIMARY KEY,company_id BIGINT NOT NULL,stage_id BIGINT NOT NULL,amount_cents BIGINT NOT NULL CHECK(amount_cents>0),
 method VARCHAR(20),transaction_no VARCHAR(160),received_date DATE NOT NULL,applicant_user_id BIGINT NOT NULL,
 voucher_file_id BIGINT,account_name VARCHAR(100),status VARCHAR(24) NOT NULL DEFAULT 'PENDING',receipt_id BIGINT,return_reason VARCHAR(500),create_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sale_declaration_company ON fr_sale_collection_declaration(company_id,status);
ALTER TABLE fr_sale_payment_stage ADD COLUMN due_date DATE, ADD COLUMN after_delivery BOOLEAN NOT NULL DEFAULT FALSE;
