-- V20261104.827：M10-T05/R1——付款「待关联现金」分层（超应付的真实资金留痕，不拒绝不绕有效账上限）。
-- 已核待关联现金 = amount 超出 payable 剩余额度的部分（分层派生：amount_cents-超额部分=已核实计入有效账；
-- 超额部分=已核待关联现金，reconcile 后可转正）。历史超付拒绝不变（迁移前数据无此状态）。
ALTER TABLE fr_fin_payment ADD COLUMN IF NOT EXISTS pending_link_cents BIGINT NOT NULL DEFAULT 0
    CHECK (pending_link_cents >= 0);
ALTER TABLE fr_fin_payment ADD COLUMN IF NOT EXISTS link_status VARCHAR(16) NOT NULL DEFAULT 'LINKED'
    CHECK (link_status IN ('LINKED', 'PENDING_LINK'));
COMMENT ON COLUMN fr_fin_payment.pending_link_cents IS '待关联现金金额（超出应付剩余额度的已核实部分；reconcile 后转正）';
COMMENT ON COLUMN fr_fin_payment.link_status IS 'LINKED=已入有效账；PENDING_LINK=已核待关联现金（异常留痕）';
