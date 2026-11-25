DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM fr_commission_handover WHERE source_mode='EXTERNAL') THEN
        RAISE EXCEPTION 'external handover records exist; preserve their evidence before rollback';
    END IF;
END $$;
DROP TRIGGER fr_external_handover_frozen ON fr_commission_handover;
DROP FUNCTION fr_external_handover_frozen();
ALTER TABLE fr_commission_handover DROP CONSTRAINT fr_handover_external_result_fk,
    DROP CONSTRAINT fr_handover_source_check,
    DROP COLUMN source_mode,DROP COLUMN external_result_version,DROP COLUMN payment_plan_snapshot;
