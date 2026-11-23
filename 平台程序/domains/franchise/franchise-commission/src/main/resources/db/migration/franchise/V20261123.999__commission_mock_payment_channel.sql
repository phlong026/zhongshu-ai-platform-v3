-- Old WECHAT/ALIPAY labels belong to simulation receipts, not actual channel transactions.
-- Preserve those historical records while new confirmations explicitly use MOCK.
ALTER TABLE fr_commission_simulated_order
    DROP CONSTRAINT fr_commission_simulated_order_channel_check;
ALTER TABLE fr_commission_simulated_order
    ADD CONSTRAINT fr_commission_simulated_order_channel_check
        CHECK (channel IN ('WECHAT', 'ALIPAY', 'MOCK'));
