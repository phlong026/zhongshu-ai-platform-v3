-- 归属保存在实际财务应付原单；旧应付没有确定的员工和薪酬所属月，保留空值。
ALTER TABLE fr_fin_payroll_record ADD CONSTRAINT uq_fr_payroll_record_company_id UNIQUE (company_id,id);
ALTER TABLE fr_fin_payable ADD COLUMN payroll_record_id bigint;
ALTER TABLE fr_fin_payable ADD CONSTRAINT fk_fr_payable_payroll_record
    FOREIGN KEY (company_id,payroll_record_id) REFERENCES fr_fin_payroll_record(company_id,id);
CREATE INDEX idx_fr_payable_payroll_record ON fr_fin_payable(company_id,payroll_record_id)
    WHERE payroll_record_id IS NOT NULL;
