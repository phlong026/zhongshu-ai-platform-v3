-- V20261101.806：付款/退款业务表交易号唯一索引迁移至 fr_fin_txn_no 身份表（更正链同号重登需要）
DROP INDEX IF EXISTS uk_fr_fin_payment_txn;
DROP INDEX IF EXISTS uk_fr_fin_refund_txn;
