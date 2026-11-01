-- 回滚 V20261102.811：撤销交付唯一索引（仅用于未上线环境或回滚演练）
DROP INDEX IF EXISTS uk_fr_sale_delivery_contract;
