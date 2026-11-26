-- 撤销 V20261126.1104。只在流程表里没有“再次咨询”事件时执行；已有记录须先另行保留。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM fr_customer_workflow_event WHERE action = 'CONSULT') THEN
        RAISE EXCEPTION 'CUSTOMER_CONSULT_IN_USE: preserve customer consultation records before rollback';
    END IF;
    DROP INDEX IF EXISTS idx_fr_customer_next_visit;
    ALTER TABLE fr_customer_workflow_event DROP CONSTRAINT IF EXISTS ck_fr_customer_workflow_event_action;
    ALTER TABLE fr_customer_workflow_event
        ADD CONSTRAINT fr_customer_workflow_event_action_check CHECK (action IN ('SAVE', 'COMPLETE', 'REOPEN', 'STATUS'));
    ALTER TABLE fr_customer_workflow_event DROP COLUMN IF EXISTS next_follow_on;
    ALTER TABLE fr_customer_workflow
        DROP COLUMN IF EXISTS status_reason_code,
        DROP COLUMN IF EXISTS status_reason,
        DROP COLUMN IF EXISTS reconsulted_at;
    ALTER TABLE fr_customer_workflow_step DROP COLUMN IF EXISTS next_follow_on;
END $$;
