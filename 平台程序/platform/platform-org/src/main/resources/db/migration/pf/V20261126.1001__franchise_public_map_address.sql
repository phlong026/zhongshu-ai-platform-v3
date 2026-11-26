ALTER TABLE pf_franchise_directory
    ADD COLUMN public_address varchar(300),
    ADD COLUMN public_address_authorized boolean NOT NULL DEFAULT false,
    ADD COLUMN public_address_authorized_by bigint,
    ADD COLUMN public_address_authorized_at timestamptz;

COMMENT ON COLUMN pf_franchise_directory.public_address IS
    '总部单独维护的可公开门店地址；不得从加盟商内部组织资料或住宅地址自动回填';
COMMENT ON COLUMN pf_franchise_directory.public_address_authorized IS
    '总部是否已明确确认该门店地址可对同品牌加盟商公开展示';
COMMENT ON COLUMN pf_franchise_directory.public_address_authorized_by IS
    '最近一次启用公开门店地址授权的后台操作人用户ID';
COMMENT ON COLUMN pf_franchise_directory.public_address_authorized_at IS
    '最近一次启用公开门店地址授权的时间';

ALTER TABLE pf_franchise_directory
    ADD CONSTRAINT ck_pf_franchise_directory_public_address_authorized
    CHECK (
        (public_address_authorized = false
            AND public_address IS NULL
            AND public_address_authorized_by IS NULL
            AND public_address_authorized_at IS NULL)
        OR
        (public_address_authorized = true
            AND public_address IS NOT NULL
            AND btrim(public_address) <> ''
            AND public_address_authorized_by IS NOT NULL
            AND public_address_authorized_at IS NOT NULL)
    );
