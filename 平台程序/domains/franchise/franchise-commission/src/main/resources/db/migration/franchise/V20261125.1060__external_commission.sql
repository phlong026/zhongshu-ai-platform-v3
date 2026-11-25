ALTER TABLE fr_commission ADD COLUMN workflow_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
 ADD COLUMN external_request_version INT, ADD COLUMN external_result_version INT;
ALTER TABLE fr_commission ADD CONSTRAINT ck_fr_commission_workflow_mode CHECK(workflow_mode IN('LEGACY','EXTERNAL')),
 ADD CONSTRAINT uk_fr_commission_company UNIQUE(id,company_id);
ALTER TABLE fr_commission DROP CONSTRAINT ck_fr_commission_status;
ALTER TABLE fr_commission ADD CONSTRAINT ck_fr_commission_status CHECK(status IN(
 'FEE_PENDING','FEE_RETURNED','DESIGNING','DESIGN_COMPLETED','DISPATCHED',
 'HANDOVER_SUBMITTED','HANDOVER_RETURNED','ACCEPTED','EXTERNAL_PENDING','EXTERNAL_READY'));

CREATE TABLE fr_commission_external_version(
 commission_id BIGINT NOT NULL, version INT NOT NULL CHECK(version>0),
 tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL CHECK(action IN('REGISTER','UPDATE','CONVERT')),
 data JSONB NOT NULL CHECK(jsonb_typeof(data)='object'), payment_plan JSONB,
 previous_status VARCHAR(24), superseded_approval_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(superseded_approval_ids)='array'),
 actor_user_id BIGINT NOT NULL, actor_post_id BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(commission_id,version),
 FOREIGN KEY(commission_id,company_id) REFERENCES fr_commission(id,company_id),
 CHECK(payment_plan IS NULL OR jsonb_typeof(payment_plan)='object')
);
CREATE INDEX idx_fr_external_commission_customer ON fr_commission_external_version(tenant_id,company_id,customer_id);
CREATE INDEX idx_fr_external_commission_files ON fr_commission_external_version USING gin((data->'attachments'));
CREATE TABLE fr_commission_external_result(
 commission_id BIGINT NOT NULL, version INT NOT NULL CHECK(version>0), request_version INT NOT NULL,
 tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
 data JSONB NOT NULL CHECK(jsonb_typeof(data)='object'),
 attachments JSONB NOT NULL CHECK(jsonb_typeof(attachments)='array' AND jsonb_array_length(attachments)=3),
 actor_user_id BIGINT NOT NULL, actor_post_id BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(commission_id,version),
 FOREIGN KEY(commission_id,request_version) REFERENCES fr_commission_external_version(commission_id,version),
 FOREIGN KEY(commission_id,company_id) REFERENCES fr_commission(id,company_id)
);
CREATE INDEX idx_fr_external_result_files ON fr_commission_external_result USING gin(attachments);
ALTER TABLE fr_commission ADD CONSTRAINT fk_fr_external_current_request FOREIGN KEY(id,external_request_version)
 REFERENCES fr_commission_external_version(commission_id,version),
 ADD CONSTRAINT fk_fr_external_current_result FOREIGN KEY(id,external_result_version)
 REFERENCES fr_commission_external_result(commission_id,version);

CREATE FUNCTION fr_external_commission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'External commission evidence is append-only'; END $$;
CREATE TRIGGER tr_fr_external_request_immutable BEFORE UPDATE OR DELETE ON fr_commission_external_version
 FOR EACH ROW EXECUTE FUNCTION fr_external_commission_immutable();
CREATE TRIGGER tr_fr_external_result_immutable BEFORE UPDATE OR DELETE ON fr_commission_external_result
 FOR EACH ROW EXECUTE FUNCTION fr_external_commission_immutable();
