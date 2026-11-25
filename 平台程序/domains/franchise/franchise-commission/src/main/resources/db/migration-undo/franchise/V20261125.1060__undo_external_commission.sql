DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM fr_commission_external_version) OR EXISTS(SELECT 1 FROM fr_commission WHERE workflow_mode='EXTERNAL')
 THEN RAISE EXCEPTION 'External commission records exist; restore a reviewed backup instead of deleting evidence'; END IF;
END $$;
ALTER TABLE fr_commission DROP CONSTRAINT fk_fr_external_current_request, DROP CONSTRAINT fk_fr_external_current_result;
DROP TABLE fr_commission_external_result;
DROP TABLE fr_commission_external_version;
DROP FUNCTION fr_external_commission_immutable();
ALTER TABLE fr_commission DROP CONSTRAINT ck_fr_commission_workflow_mode, DROP CONSTRAINT uk_fr_commission_company;
ALTER TABLE fr_commission DROP COLUMN workflow_mode, DROP COLUMN external_request_version, DROP COLUMN external_result_version;
ALTER TABLE fr_commission DROP CONSTRAINT ck_fr_commission_status;
ALTER TABLE fr_commission ADD CONSTRAINT ck_fr_commission_status CHECK(status IN(
 'FEE_PENDING','FEE_RETURNED','DESIGNING','DESIGN_COMPLETED','DISPATCHED','HANDOVER_SUBMITTED','HANDOVER_RETURNED','ACCEPTED'));
