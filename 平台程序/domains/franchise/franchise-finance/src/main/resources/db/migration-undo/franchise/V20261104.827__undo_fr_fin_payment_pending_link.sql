-- 回滚 V20261104.827：付款待关联现金分层
ALTER TABLE fr_fin_payment DROP COLUMN IF EXISTS pending_link_cents;
ALTER TABLE fr_fin_payment DROP COLUMN IF EXISTS link_status;
