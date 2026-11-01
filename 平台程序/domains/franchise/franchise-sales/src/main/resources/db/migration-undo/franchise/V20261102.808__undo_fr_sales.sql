-- 回滚 V20261102.808：业务交易流五表（依赖 fr_customer，须先于 802 回滚前执行）
DROP TABLE IF EXISTS fr_sale_delivery;
DROP TABLE IF EXISTS fr_sale_payment_stage;
DROP TABLE IF EXISTS fr_sale_contract;
DROP TABLE IF EXISTS fr_sale_quote;
DROP TABLE IF EXISTS fr_sale_requirement;
