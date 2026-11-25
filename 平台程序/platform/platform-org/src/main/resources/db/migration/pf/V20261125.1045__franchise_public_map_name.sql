ALTER TABLE pf_franchise_directory ADD COLUMN public_name varchar(128);
COMMENT ON COLUMN pf_franchise_directory.public_name IS '总部确认的公开地图名称；未配置时仅由品牌和标准行政区派生，禁止回退到含人员信息的内部名称';
