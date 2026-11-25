-- 规则由管理层提供，财务登记；不存在自动工资公式或资金支付。
CREATE TABLE fr_fin_payroll_rule (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    job_family VARCHAR(16) NOT NULL CHECK (job_family IN ('BUSINESS','ENGINEERING')),
    version BIGINT NOT NULL CHECK (version > 0),
    effective_month VARCHAR(7) NOT NULL CHECK (effective_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    base_salary_note VARCHAR(2000) NOT NULL CHECK (length(trim(base_salary_note)) > 0),
    assessment_note VARCHAR(2000) NOT NULL CHECK (length(trim(assessment_note)) > 0),
    commission_note VARCHAR(2000) NOT NULL CHECK (length(trim(commission_note)) > 0),
    evidence_note VARCHAR(1000) NOT NULL CHECK (length(trim(evidence_note)) > 0),
    calculation_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL_ONLY' CHECK (calculation_mode='MANUAL_ONLY'),
    author_user_id BIGINT NOT NULL, author_post_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(company_id,job_family,version)
);
CREATE INDEX idx_fr_payroll_rule_effective ON fr_fin_payroll_rule(company_id,job_family,effective_month DESC,version DESC);
CREATE TABLE fr_fin_payroll_record (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    job_family VARCHAR(16) NOT NULL CHECK (job_family IN ('BUSINESS','ENGINEERING')),
    month VARCHAR(7) NOT NULL CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    version BIGINT NOT NULL CHECK (version > 0),
    rule_id BIGINT NULL REFERENCES fr_fin_payroll_rule(id),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
    note VARCHAR(1000) NOT NULL CHECK (length(trim(note)) > 0),
    author_user_id BIGINT NOT NULL, author_post_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(company_id,user_id,job_family,month,version)
);
CREATE INDEX idx_fr_payroll_record_period ON fr_fin_payroll_record(company_id,month DESC,user_id,job_family,version DESC);
CREATE FUNCTION fr_payroll_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Payroll rules and records are immutable; append a new version';
END;
$$;
CREATE TRIGGER trg_fr_payroll_rule_immutable BEFORE UPDATE OR DELETE ON fr_fin_payroll_rule
    FOR EACH ROW EXECUTE FUNCTION fr_payroll_snapshot_immutable();
CREATE TRIGGER trg_fr_payroll_record_immutable BEFORE UPDATE OR DELETE ON fr_fin_payroll_record
    FOR EACH ROW EXECUTE FUNCTION fr_payroll_snapshot_immutable();
