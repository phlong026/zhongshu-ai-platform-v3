BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pf_recharge_order) OR EXISTS (SELECT 1 FROM pf_payment_notification_inbox) THEN
        RAISE EXCEPTION 'Payment data exists; schema rollback refused';
    END IF;
END $$;
DROP TABLE pf_payment_anomaly_audit;
DROP TABLE pf_payment_notification_inbox;
DROP TABLE pf_recharge_credit;
DROP TABLE pf_payment_transaction;
DROP TABLE pf_payment_attempt;
DROP TABLE pf_recharge_order;
ALTER TABLE pf_recharge_plan DROP COLUMN wx_product_id;
COMMIT;
