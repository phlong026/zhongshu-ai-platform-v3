ALTER TABLE pf_training_material ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE pf_training_material ADD COLUMN deleted_at timestamptz;
ALTER TABLE pf_training_material ADD COLUMN deleted_by bigint;
CREATE INDEX pf_training_material_active ON pf_training_material(tenant_id,created_at DESC) WHERE deleted_at IS NULL;
