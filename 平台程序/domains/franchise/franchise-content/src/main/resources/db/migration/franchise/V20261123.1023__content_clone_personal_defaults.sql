-- Defaults are a user's preference for a persona, independently for avatar and voice.
-- Keep the old company-wide defaults as historical data for older release rollback.
CREATE TABLE fr_content_clone_personal_default (
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    persona_id BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('AVATAR','VOICE')),
    clone_id BIGINT NOT NULL,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id,user_id,persona_id,kind)
);
CREATE INDEX idx_fr_clone_personal_default_resource
    ON fr_content_clone_personal_default(company_id,clone_id);

-- An old shared choice only proves a personal preference when the chooser owns it.
INSERT INTO fr_content_clone_personal_default(company_id,user_id,persona_id,kind,clone_id,update_time)
SELECT d.company_id,d.updated_by,d.persona_id,d.kind,d.clone_id,d.update_time
FROM fr_content_clone_default d
JOIN fr_content_digital_clone c ON c.id=d.clone_id AND c.company_id=d.company_id
    AND c.persona_id=d.persona_id AND c.kind=d.kind AND c.author_user_id=d.updated_by
JOIN fr_content_persona p ON p.id=d.persona_id AND p.company_id=d.company_id
    AND p.enabled=TRUE AND p.deleted=FALSE
WHERE c.status='READY' AND c.billing_state='SETTLED'
    AND c.provider_resource_id IS NOT NULL AND btrim(c.provider_resource_id)<>'';
