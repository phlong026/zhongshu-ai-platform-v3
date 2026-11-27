ALTER TABLE fr_customer
    ADD COLUMN sales_stage varchar(24) CHECK (sales_stage IN ('NEW','CONTACTED','PROPOSAL','QUOTED','SIGNED')),
    ADD COLUMN visited boolean NOT NULL DEFAULT false;
ALTER TABLE fr_customer_follow
    ADD COLUMN outcome varchar(24) CHECK (outcome IN ('UNREACHED','AWAITING_REPLY','ARRIVED','DEFERRED'));

UPDATE fr_customer c SET visited = true
WHERE EXISTS (SELECT 1 FROM fr_customer_follow f
              WHERE f.customer_id = c.id AND f.company_id = c.company_id AND f.contact_kind = 'VISIT');
