BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_design_billing) THEN
        RAISE EXCEPTION 'Design billing records exist; retain reservations and roll back application only';
    END IF;
END $$;
DROP TABLE fr_design_billing;
DROP TABLE fr_design_price;
COMMIT;
