-- T26-10: immutable notification facts; reading a message never completes its business action.
ALTER TABLE user_message ADD COLUMN fact_version VARCHAR(128);
ALTER TABLE user_message ADD COLUMN occurred_at TIMESTAMPTZ;
ALTER TABLE user_message ADD COLUMN action_type VARCHAR(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE user_message ADD COLUMN target_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE user_message ADD COLUMN fact_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE user_message ADD CONSTRAINT ck_message_action_type CHECK
    (action_type IN ('NONE','SELECT_FLAT','SELECT_ELEVATION','RESUBMIT_SUBMISSION'));
ALTER TABLE user_message ADD CONSTRAINT ck_message_fact_identity CHECK
    (fact_version IS NULL OR (biz_type IS NOT NULL AND biz_id IS NOT NULL AND length(fact_version)>0));
CREATE UNIQUE INDEX uk_user_message_fact_recipient
    ON user_message(tenant_id,user_id,message_type,biz_type,biz_id,fact_version)
    WHERE fact_version IS NOT NULL;
CREATE INDEX idx_user_message_actions ON user_message(user_id,id DESC)
    WHERE deleted=FALSE AND action_type<>'NONE';
-- Existing messages/receipts and all business/monetary tables remain untouched.
