-- 八步流程成为客户跟进的唯一事实来源（2026-11-26 决定）。
-- 1) 每条步骤记录可以约定“下次跟进日期”；客户表的 next_visit_date / last_follow_at / status 由八步服务同步，
--    首页、待办、列表筛选只读客户表上的这份投影。
-- 2) 流失、暂缓带原因码与说明；流失客户再次登记客资时记下时间，供负责人待办使用。
-- 3) 客资关联到老客户时追加一条“再次咨询”事件（CONSULT），进入客户的跟进时间线。
ALTER TABLE fr_customer_workflow_step ADD COLUMN next_follow_on date;
ALTER TABLE fr_customer_workflow_event ADD COLUMN next_follow_on date;
ALTER TABLE fr_customer_workflow
    ADD COLUMN status_reason_code varchar(32),
    ADD COLUMN status_reason varchar(1000),
    ADD COLUMN reconsulted_at timestamptz;

DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'fr_customer_workflow_event'::regclass AND contype = 'c' AND conname LIKE '%action%';
    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'fr_customer_workflow_event action constraint not found';
    END IF;
    EXECUTE format('ALTER TABLE fr_customer_workflow_event DROP CONSTRAINT %I', constraint_name);
END $$;
ALTER TABLE fr_customer_workflow_event
    ADD CONSTRAINT ck_fr_customer_workflow_event_action
    CHECK (action IN ('SAVE', 'COMPLETE', 'REOPEN', 'STATUS', 'CONSULT'));

-- “今日待跟进 / 预约跟进”按门店和下次跟进日期筛选。
CREATE INDEX idx_fr_customer_next_visit ON fr_customer (company_id, next_visit_date);
