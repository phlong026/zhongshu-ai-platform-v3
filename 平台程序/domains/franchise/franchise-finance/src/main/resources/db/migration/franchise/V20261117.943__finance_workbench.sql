-- 真实资金仍在原收付表；账本只补充归属，不复制历史资金。
CREATE TABLE fr_fin_book (
 id BIGINT PRIMARY KEY, company_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL,
 customer_id BIGINT, commission_id BIGINT, project_id BIGINT,
 create_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_fin_book_project ON fr_fin_book(company_id,project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_fin_book_company ON fr_fin_book(company_id);
ALTER TABLE fr_fin_receipt ADD COLUMN book_id BIGINT REFERENCES fr_fin_book(id),
 ADD COLUMN category VARCHAR(40), ADD COLUMN account_name VARCHAR(100), ADD COLUMN counterparty VARCHAR(160);
ALTER TABLE fr_fin_payable ADD COLUMN book_id BIGINT REFERENCES fr_fin_book(id),
 ADD COLUMN category VARCHAR(40), ADD COLUMN business_date DATE, ADD COLUMN due_date DATE;
ALTER TABLE fr_fin_payment ADD COLUMN funding_type VARCHAR(16) NOT NULL DEFAULT 'COMPANY',
 ADD COLUMN payer_user_id BIGINT, ADD COLUMN payer_name VARCHAR(100), ADD COLUMN account_name VARCHAR(100);
ALTER TABLE fr_fin_payment ADD CONSTRAINT ck_fin_payment_funding CHECK (
 funding_type IN ('COMPANY','EMPLOYEE') AND (funding_type <> 'EMPLOYEE' OR (payer_user_id IS NOT NULL AND payer_name IS NOT NULL)));
ALTER TABLE fr_fin_refund ADD COLUMN funding_type VARCHAR(16) NOT NULL DEFAULT 'COMPANY';
ALTER TABLE fr_fin_refund ADD CONSTRAINT ck_fin_refund_funding CHECK (funding_type IN ('COMPANY','EMPLOYEE'));
CREATE UNIQUE INDEX uk_fin_reimbursement_origin ON fr_fin_payable(company_id,source_ref) WHERE source_type='reimbursement';
CREATE INDEX idx_fin_receipt_book ON fr_fin_receipt(company_id,book_id);
CREATE INDEX idx_fin_payable_book ON fr_fin_payable(company_id,book_id);

-- 取消后的结算义务允许为零；新增应付仍由命令校验为正。
ALTER TABLE fr_fin_payable DROP CONSTRAINT fr_fin_payable_total_cents_check;
ALTER TABLE fr_fin_payable ADD CONSTRAINT fr_fin_payable_total_cents_check CHECK(total_cents>=0 AND total_cents<=1000000000000);
