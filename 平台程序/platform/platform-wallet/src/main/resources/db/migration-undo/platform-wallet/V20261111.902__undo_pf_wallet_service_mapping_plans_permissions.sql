BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pf_org_service_account) OR EXISTS (SELECT 1 FROM pf_recharge_plan) THEN
        RAISE EXCEPTION 'Wallet service mappings/plans exist; schema rollback refused';
    END IF;
END $$;
DELETE FROM system_role_menu WHERE menu_id IN (910100, 910101, 910102);
DELETE FROM system_menu WHERE id IN (910100, 910101, 910102);
DROP TABLE pf_recharge_plan;
DROP TABLE pf_org_service_account;
COMMIT;
