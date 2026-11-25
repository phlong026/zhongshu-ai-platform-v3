ALTER TABLE pf_announcement
    ADD COLUMN create_request_id varchar(36),
    ADD COLUMN create_request_fingerprint varchar(64),
    ADD CONSTRAINT pf_announcement_create_request_pair
        CHECK ((create_request_id IS NULL) = (create_request_fingerprint IS NULL)),
    ADD CONSTRAINT pf_announcement_create_request_unique UNIQUE (tenant_id, created_by, create_request_id);
