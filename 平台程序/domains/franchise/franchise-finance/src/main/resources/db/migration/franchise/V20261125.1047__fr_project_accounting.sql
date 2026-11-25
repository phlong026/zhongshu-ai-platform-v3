-- R14：确认快照不产生收付事实，历史版本不随原账或后续规则改变。
CREATE TABLE fr_fin_project_accounting (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    source_hash VARCHAR(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    note VARCHAR(1000) NOT NULL CHECK (length(trim(note)) > 0),
    confirmed_by BIGINT NOT NULL,
    confirmed_post_id BIGINT NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, project_id, version)
);
CREATE FUNCTION fr_project_accounting_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Project accounting snapshots are immutable; create a new confirmation version';
END;
$$;
CREATE TRIGGER trg_fr_project_accounting_immutable
    BEFORE UPDATE OR DELETE ON fr_fin_project_accounting
    FOR EACH ROW EXECUTE FUNCTION fr_project_accounting_immutable();
