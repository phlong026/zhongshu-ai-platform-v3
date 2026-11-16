-- Capture and customer carry the same address snapshot; linking a lead does not overwrite the existing customer.
ALTER TABLE fr_customer
    ALTER COLUMN province TYPE VARCHAR(64),
    ALTER COLUMN city TYPE VARCHAR(64),
    ADD COLUMN district VARCHAR(64),
    ADD COLUMN town VARCHAR(64),
    ADD COLUMN village VARCHAR(64),
    ADD COLUMN region_code VARCHAR(12),
    ADD COLUMN address_detail VARCHAR(200);

ALTER TABLE fr_lead
    ADD COLUMN province VARCHAR(64),
    ADD COLUMN city VARCHAR(64),
    ADD COLUMN district VARCHAR(64),
    ADD COLUMN town VARCHAR(64),
    ADD COLUMN village VARCHAR(64),
    ADD COLUMN region_code VARCHAR(12),
    ADD COLUMN address_detail VARCHAR(200);
