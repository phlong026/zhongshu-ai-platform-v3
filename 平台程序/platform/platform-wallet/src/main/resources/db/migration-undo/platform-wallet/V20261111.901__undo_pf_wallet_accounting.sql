-- 仅供尚无业务数据的部署回退；有余额/流水时必须先停写和备份，不允许用 schema 回退清账。
BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pf_point_ledger) OR EXISTS (SELECT 1 FROM pf_wallet_charge)
        OR EXISTS (SELECT 1 FROM pf_point_account WHERE available_points <> 0 OR reserved_points <> 0) THEN
        RAISE EXCEPTION 'Wallet contains accounting data; schema rollback refused';
    END IF;
END $$;
DROP TABLE pf_wallet_charge;
DROP TABLE pf_point_ledger;
DROP FUNCTION pf_wallet_ledger_append_only();
DROP TABLE pf_point_account;
DROP TABLE pf_wallet;
COMMIT;
