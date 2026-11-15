CREATE TABLE fr_design_pricing_policy (
 id BIGINT PRIMARY KEY, design_org_id BIGINT NOT NULL, version INT NOT NULL,
 rate_cents BIGINT NOT NULL CHECK(rate_cents > 0 AND rate_cents <= 10000000000),
 scope VARCHAR(500) NOT NULL, recipient VARCHAR(64) NOT NULL, active BOOLEAN NOT NULL,
 actor_user_id BIGINT NOT NULL, actor_post_id BIGINT NOT NULL,
 create_time TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(design_org_id,version)
);
ALTER TABLE fr_commission_submission ADD COLUMN mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY_SPLIT',
 ADD COLUMN rate_cents BIGINT, ADD COLUMN pricing_version INT, ADD COLUMN attachment_asset_id BIGINT;
ALTER TABLE fr_commission_submission DROP CONSTRAINT ck_fr_commission_submission_amount;
ALTER TABLE fr_commission_submission ADD CONSTRAINT ck_fr_commission_submission_amount CHECK (
 (mode='LEGACY_SPLIT' AND first_cents>0 AND total_cents>first_cents) OR
 (mode='AREA_FULL' AND first_cents=0 AND total_cents>0
  AND area IS NOT NULL AND area>0 AND rate_cents IS NOT NULL AND rate_cents>0
  AND pricing_version IS NOT NULL AND pricing_version>0));
