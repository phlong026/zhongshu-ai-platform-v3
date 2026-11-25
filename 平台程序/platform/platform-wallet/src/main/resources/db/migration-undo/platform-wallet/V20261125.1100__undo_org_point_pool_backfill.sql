DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM org_point_pool_backfill) THEN
  RAISE EXCEPTION 'Provisioning audit exists; preserve pools and restore a reviewed backup';
 END IF;
END $$;
DROP TABLE org_point_pool_backfill;
DROP FUNCTION org_point_pool_backfill_immutable();
