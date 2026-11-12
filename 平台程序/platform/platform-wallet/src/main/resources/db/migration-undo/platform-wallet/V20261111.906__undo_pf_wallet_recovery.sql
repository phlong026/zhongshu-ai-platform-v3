-- 先停后台worker，再回滚；本脚本不删除订单、支付、积分事实。
DROP INDEX idx_pf_payment_inbox_due;
ALTER TABLE pf_payment_notification_inbox DROP COLUMN next_attempt_at;
DROP TABLE pf_wallet_recovery_task;
