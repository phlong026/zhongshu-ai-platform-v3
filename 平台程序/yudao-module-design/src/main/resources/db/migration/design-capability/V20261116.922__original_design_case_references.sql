CREATE TABLE design_capability_case_image (
    owner_id BIGINT NOT NULL, request_key VARCHAR(120) NOT NULL, asset_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(owner_id,request_key)
);
CREATE TABLE design_capability_case_mirror (
    owner_id BIGINT NOT NULL, source_case_id BIGINT NOT NULL, source_version_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL, version_id BIGINT NOT NULL, line VARCHAR(16) NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL, validated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id,source_case_id,source_version_id), UNIQUE(owner_id,case_id)
);
