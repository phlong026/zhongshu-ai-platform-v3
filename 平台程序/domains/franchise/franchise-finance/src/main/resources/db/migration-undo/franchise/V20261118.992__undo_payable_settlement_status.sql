DROP TRIGGER IF EXISTS fr_payable_amount_settlement ON fr_fin_payable;
DROP TRIGGER IF EXISTS fr_recovery_settlement ON fr_fin_receipt;
DROP TRIGGER IF EXISTS fr_expense_refund_settlement ON fr_fin_refund;
DROP TRIGGER IF EXISTS fr_payment_settlement ON fr_fin_payment;
DROP FUNCTION IF EXISTS fr_fin_payable_fact_changed();
DROP FUNCTION IF EXISTS fr_fin_refresh_payment_bills(bigint,bigint,bigint);
DROP FUNCTION IF EXISTS fr_fin_refresh_payable(bigint,bigint);
