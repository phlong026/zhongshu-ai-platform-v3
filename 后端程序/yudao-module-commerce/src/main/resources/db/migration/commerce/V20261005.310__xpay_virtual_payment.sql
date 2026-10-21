-- V20261005.310：小程序虚拟支付（provider=xpay，wx.requestVirtualPayment）接入
-- 用途：recharge_plan 增加微信虚拟支付「道具编号」映射。
--   虚拟支付按「道具」售卖：每个充值方案必须对应 MP 后台「虚拟支付 → 道具管理」中
--   已创建并发布的道具（道具单价必须与方案 amount_cents 一致，单位分），
--   下单时 signData.productId 取本列，goodsPrice = amount_cents。
-- 语义：可空——为空时 xpay 通道下单直接拒绝（提示运营补配置）；stub/real 通道不消费本列。
-- 维护：管理后台「充值方案」编辑接口（PATCH wxProductId）与小程序后台道具发布后同步填写。

ALTER TABLE recharge_plan ADD COLUMN IF NOT EXISTS wx_product_id VARCHAR(64) NULL;

COMMENT ON COLUMN recharge_plan.wx_product_id IS
    '微信虚拟支付道具编号（MP 后台道具管理创建并发布的 ProductId）；xpay 通道下单必需，与 amount_cents 同价';
