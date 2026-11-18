-- The simulation receipt is the test-points ledger. Formal reservations only consult org_point_account.
CREATE TABLE org_point_simulated_purchase (
    id bigint PRIMARY KEY, tenant_id bigint NOT NULL, org_id bigint NOT NULL,
    biz_key text NOT NULL UNIQUE, price_fen bigint NOT NULL CHECK (price_fen >= 0),
    test_points bigint NOT NULL CHECK (test_points >= 0), receipt jsonb NOT NULL,
    create_time timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sim_purchase_org ON org_point_simulated_purchase (tenant_id, org_id);

-- Only the table owner can enter finance evidence, including on installations
-- that grant the runtime role DML on public tables.
CREATE TABLE org_point_simulation_review (
    source_ledger_id bigint PRIMARY KEY REFERENCES org_point_ledger(id),
    approved_transfer bigint NOT NULL CHECK (approved_transfer >= 0),
    reviewed_ledger_count bigint NOT NULL CHECK (reviewed_ledger_count > 0),
    reviewed_balance bigint NOT NULL CHECK (reviewed_balance >= 0),
    finance_reviewer text NOT NULL CHECK (length(trim(finance_reviewer)) > 0),
    evidence_reference text NOT NULL CHECK (length(trim(evidence_reference)) > 0),
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION require_simulation_review_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user <> pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid=TG_RELID)) THEN
        RAISE EXCEPTION 'Recording financial review requires the migration owner';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER simulation_review_owner BEFORE INSERT OR UPDATE OR DELETE ON org_point_simulation_review
FOR EACH ROW EXECUTE FUNCTION require_simulation_review_owner();
