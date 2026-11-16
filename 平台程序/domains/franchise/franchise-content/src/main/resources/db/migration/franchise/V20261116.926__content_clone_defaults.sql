-- Each company/person has one default avatar and one default voice. Clone facts remain append-only.
CREATE TABLE fr_content_clone_default (
    company_id BIGINT NOT NULL,
    persona_id BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('AVATAR','VOICE')),
    clone_id BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id,persona_id,kind)
);
CREATE INDEX idx_fr_content_clone_default_resource ON fr_content_clone_default(company_id,clone_id);
