-- Explicit, authorized zero-balance provisioning for existing companies; no balance or ledger migration.
CREATE TABLE org_point_pool_backfill (
 id BIGINT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 org_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 existed BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,request_key)
);
CREATE FUNCTION org_point_pool_backfill_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Company pool provisioning audit is append-only'; END $$;
CREATE TRIGGER tr_org_point_pool_backfill_immutable BEFORE UPDATE OR DELETE ON org_point_pool_backfill
 FOR EACH ROW EXECUTE FUNCTION org_point_pool_backfill_immutable();
