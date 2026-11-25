-- External packets reference the registered result version, never a fabricated design dispatch.
ALTER TABLE fr_commission_handover
    ADD COLUMN source_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN external_result_version INTEGER,
    ADD COLUMN payment_plan_snapshot JSONB,
    ADD CONSTRAINT fr_handover_external_result_fk FOREIGN KEY (commission_id,external_result_version)
        REFERENCES fr_commission_external_result(commission_id,version),
    ADD CONSTRAINT fr_handover_source_check CHECK (
        (source_mode='LEGACY' AND external_result_version IS NULL AND payment_plan_snapshot IS NULL)
        OR (source_mode='EXTERNAL' AND dispatch_seq=0 AND external_result_version IS NOT NULL AND external_result_version>0
            AND confirmation_asset_id IS NOT NULL AND confirmation_asset_id>0
            AND result_file ~ '^[1-9][0-9]{0,18}$' AND drawing_file ~ '^[1-9][0-9]{0,18}$'
            AND review_file ~ '^[1-9][0-9]{0,18}$' AND confirmation_file=confirmation_asset_id::text
            AND (payment_plan_snapshot IS NULL OR jsonb_typeof(payment_plan_snapshot)='object')));

ALTER TABLE fr_commission_handover DROP CONSTRAINT ck_fr_commission_handover_confirmation;
ALTER TABLE fr_commission_handover ADD CONSTRAINT ck_fr_commission_handover_confirmation
    CHECK (confirmation_date <= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date);

-- Decisions may progress, but evidence, intended receiver and payment terms remain the submitted snapshot.
CREATE FUNCTION fr_external_handover_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.source_mode='EXTERNAL' AND (TG_OP='DELETE' OR
        (to_jsonb(NEW)-ARRAY['status','decision_note','decided_by_user_id','decided_at']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['status','decision_note','decided_by_user_id','decided_at'])) THEN
        RAISE EXCEPTION 'external handover evidence is immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fr_external_handover_frozen BEFORE UPDATE OR DELETE ON fr_commission_handover
    FOR EACH ROW EXECUTE FUNCTION fr_external_handover_frozen();
