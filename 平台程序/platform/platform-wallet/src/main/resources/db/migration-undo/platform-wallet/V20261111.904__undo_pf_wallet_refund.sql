-- 仅供隔离/停写环境按依赖逆序回滚；相关充值单、退款单或通知非空时必须保留事实并前滚。
-- 单个DO块保证独立执行原子性，也参与调用方事务，不提前COMMIT；普通DROP保留依赖保护。
DO $$ BEGIN
    -- 旧快照即使拿到表锁也可能看不到刚提交的通知；不允许据此判定空表。
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Refund schema rollback requires READ COMMITTED isolation';
    END IF;
    LOCK TABLE pf_recharge_order, pf_payment_notification_inbox, pf_refund_order IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM pf_recharge_order)
        OR EXISTS (SELECT 1 FROM pf_payment_notification_inbox)
        OR EXISTS (SELECT 1 FROM pf_refund_order) THEN
        RAISE EXCEPTION 'Payment, refund or notification data exists; refund schema rollback refused';
    END IF;
    DROP TABLE pf_refund_order;
END $$;
