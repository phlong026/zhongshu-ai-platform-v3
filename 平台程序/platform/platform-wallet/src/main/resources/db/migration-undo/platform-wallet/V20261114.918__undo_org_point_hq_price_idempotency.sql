-- V20261114.918 undo only applies before any price-publication request fact exists.
LOCK TABLE org_point_hq_price_request IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM org_point_hq_price_request) THEN
    RAISE EXCEPTION 'refuse undo: headquarters price idempotency facts exist';
  END IF;
END $$;
DROP TRIGGER IF EXISTS trg_ophpr_append_only ON org_point_hq_price_request;
DROP FUNCTION IF EXISTS org_point_hq_price_request_append_only();
DROP TABLE IF EXISTS org_point_hq_price_request;
