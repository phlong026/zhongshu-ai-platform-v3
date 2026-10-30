-- V20261005.310 回滚：移除 recharge_plan 的微信虚拟支付道具编号列
ALTER TABLE recharge_plan DROP COLUMN IF EXISTS wx_product_id;
