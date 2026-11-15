CREATE TABLE fr_commission_dispatch_manifest (
 id BIGINT PRIMARY KEY, commission_id BIGINT NOT NULL REFERENCES fr_commission(id),
 dispatch_seq INT NOT NULL, submission_version INT NOT NULL,
 design_org_id BIGINT NOT NULL, result_asset_id BIGINT NOT NULL,
 drawing_asset_id BIGINT NOT NULL, review_asset_id BIGINT NOT NULL,
 drawing_version VARCHAR(64) NOT NULL, actor_user_id BIGINT NOT NULL,
 actor_post_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(commission_id,dispatch_seq)
);
ALTER TABLE fr_commission_handover ADD COLUMN confirmation_asset_id BIGINT;
