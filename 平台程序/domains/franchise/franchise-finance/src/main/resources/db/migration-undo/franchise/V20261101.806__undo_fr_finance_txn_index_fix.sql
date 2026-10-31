-- 回滚 V20261101.806
CREATE UNIQUE INDEX uk_fr_fin_payment_txn ON fr_fin_payment (company_id, transaction_no) WHERE transaction_no IS NOT NULL;
CREATE UNIQUE INDEX uk_fr_fin_refund_txn ON fr_fin_refund (company_id, transaction_no) WHERE transaction_no IS NOT NULL;
