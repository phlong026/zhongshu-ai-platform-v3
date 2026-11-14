-- V20261114.917 undo is guarded because HQ approvals and price audit are financial facts.
LOCK TABLE org_point_hq_adjustment IN ACCESS EXCLUSIVE MODE;
LOCK TABLE org_point_price_audit IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM org_point_hq_adjustment)
     OR EXISTS (SELECT 1 FROM org_point_price_audit) THEN
    RAISE EXCEPTION 'refuse undo: headquarters company-point facts exist';
  END IF;
END $$;
DROP TRIGGER IF EXISTS trg_oppa_append_only ON org_point_price_audit;
DROP FUNCTION IF EXISTS org_point_price_audit_append_only();
DROP TABLE IF EXISTS org_point_price_audit;
DROP TRIGGER IF EXISTS trg_opha_immutable ON org_point_hq_adjustment;
DROP FUNCTION IF EXISTS org_point_hq_adjustment_immutable();
DROP TABLE IF EXISTS org_point_hq_adjustment;
DELETE FROM system_role_menu WHERE menu_id IN (917000,917001,917002,917003,917004);
DELETE FROM system_menu WHERE id IN (917000,917001,917002,917003,917004);
