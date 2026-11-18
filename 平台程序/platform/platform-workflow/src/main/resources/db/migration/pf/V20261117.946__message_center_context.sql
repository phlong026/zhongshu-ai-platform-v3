-- Business facts are immutable notification snapshots; current actions come from TodoService.
ALTER TABLE pf_message ADD COLUMN business_context JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE pf_message ADD COLUMN received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_pf_message_org_user_cursor ON pf_message(org_id,user_id,id DESC);
CREATE INDEX idx_pf_message_org_user_unread ON pf_message(org_id,user_id,id DESC) WHERE read=FALSE;
ALTER TABLE pf_message DROP CONSTRAINT ck_pf_message_route;
ALTER TABLE pf_message ADD CONSTRAINT ck_pf_message_route CHECK(route IN(
 'customer','course','personal-performance','privacy','design-dispatch','design-progress',
 'commission','content-task','commission-payment','engineering','finance','sales-contract','organization'
));
CREATE TABLE pf_message_delivery_checkpoint (
 event_id BIGINT PRIMARY KEY,
 delivered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE pf_message_center_activation (activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO pf_message_center_activation DEFAULT VALUES;
-- Existing deliveries remain read/unread as they are. Never flood historical events on upgrade.
INSERT INTO pf_message_delivery_checkpoint(event_id)
 SELECT DISTINCT split_part(dedupe_key,':',2)::bigint FROM pf_message
 WHERE sender_domain='fr-business' AND dedupe_key ~ '^command:[0-9]+:'
 ON CONFLICT DO NOTHING;
