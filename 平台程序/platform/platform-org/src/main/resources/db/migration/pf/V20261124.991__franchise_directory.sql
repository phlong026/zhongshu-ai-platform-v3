-- A source row is one directory record, even when it covers several districts.
-- Customer workbooks are imported through the authenticated API, never seeded here.
CREATE TABLE pf_franchise_directory (
  id varchar(36) PRIMARY KEY,
  tenant_id bigint NOT NULL,
  brand_id bigint NOT NULL,
  name varchar(128) NOT NULL,
  record_type varchar(16) NOT NULL CHECK(record_type IN ('FRANCHISE','COVERAGE')),
  original_region_text varchar(500) NOT NULL DEFAULT '',
  match_status varchar(16) NOT NULL CHECK(match_status IN ('RESOLVED','PARTIAL','UNRESOLVED')),
  province_code varchar(6), city_code varchar(6),
  source_key varchar(180) NOT NULL,
  source_fingerprint varchar(64),
  source_row integer, source_sheet varchar(80), source_cell varchar(20),
  account_id varchar(36) UNIQUE REFERENCES pf_franchise_account(account_id) ON DELETE SET NULL,
  org_id bigint,
  version bigint NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  created_by bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,source_key), UNIQUE(tenant_id,org_id),
  FOREIGN KEY(tenant_id,brand_id) REFERENCES pf_map_brand(tenant_id,id)
);
CREATE INDEX pf_franchise_directory_brand ON pf_franchise_directory(tenant_id,brand_id);
CREATE TABLE pf_franchise_directory_region (
  tenant_id bigint NOT NULL,
  record_id varchar(36) NOT NULL,
  region_code varchar(6) NOT NULL CHECK(region_code ~ '^[0-9]{6}$'),
  PRIMARY KEY(record_id,region_code),
  FOREIGN KEY(tenant_id,record_id) REFERENCES pf_franchise_directory(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX pf_franchise_directory_region_scope ON pf_franchise_directory_region(tenant_id,region_code);

-- Do not keep an old explicitly selected district after the profile's location changed.
CREATE FUNCTION pf_franchise_directory_invalidate_district() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.province,NEW.city,NEW.area) IS DISTINCT FROM (OLD.province,OLD.city,OLD.area)
     AND NEW.map_district_code IS NOT DISTINCT FROM OLD.map_district_code THEN
    NEW.map_district_code := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pf_franchise_directory_invalidate_district BEFORE UPDATE OF province,city,area
  ON pf_franchise_account FOR EACH ROW EXECUTE FUNCTION pf_franchise_directory_invalidate_district();

CREATE FUNCTION pf_franchise_directory_project(a pf_franchise_account, reset_regions boolean DEFAULT true) RETURNS void LANGUAGE plpgsql AS $$
DECLARE location pf_store_location; region pf_map_region; entry_id varchar(36); pc text; cc text;
BEGIN
  IF a.account_type <> 'BOSS' OR btrim(a.brand)='' THEN
    UPDATE pf_franchise_directory SET active=false,version=version+1,updated_at=now() WHERE account_id=a.account_id;
    RETURN;
  END IF;
  SELECT * INTO location FROM pf_store_location WHERE account_id=a.account_id;
  IF location.account_id IS NULL THEN RETURN; END IF;
  -- A linked source record owns its coverage independently of the account's primary location.
  -- Only the directory edit API may replace those associations; auto-projected legacy rows follow the account.
  IF EXISTS(SELECT 1 FROM pf_franchise_directory d
            WHERE d.account_id=a.account_id AND d.source_key<>'account:'||a.account_id) THEN
    reset_regions:=false;
  END IF;
  SELECT * INTO region FROM pf_map_region WHERE code=location.region_code;
  SELECT CASE WHEN count(DISTINCT province_code)=1 THEN min(province_code) END,
         CASE WHEN count(DISTINCT city_code)=1 THEN min(city_code) END
    INTO pc,cc FROM pf_map_region r WHERE pf_map_matches_city(r,a.province,a.city);
  IF cc=pc OR (cc=location.region_code AND right(cc,2)<>'00') THEN cc:=NULL; END IF;
  INSERT INTO pf_franchise_directory(id,tenant_id,brand_id,name,record_type,original_region_text,
      match_status,province_code,city_code,source_key,account_id,org_id,created_by)
    VALUES(a.account_id,a.tenant_id,location.brand_id,a.org_name,'FRANCHISE',a.area,
      CASE WHEN location.region_code IS NULL THEN 'UNRESOLVED'
           WHEN right(location.region_code,2)='00' THEN 'PARTIAL' ELSE 'RESOLVED' END,
      pc,cc,'account:'||a.account_id,a.account_id,a.org_id,a.created_by)
    ON CONFLICT(account_id) DO UPDATE SET name=excluded.name,brand_id=excluded.brand_id,
      record_type='FRANCHISE',active=true,
      original_region_text=CASE WHEN reset_regions THEN excluded.original_region_text ELSE pf_franchise_directory.original_region_text END,
      province_code=CASE WHEN reset_regions THEN excluded.province_code ELSE pf_franchise_directory.province_code END,
      city_code=CASE WHEN reset_regions THEN excluded.city_code ELSE pf_franchise_directory.city_code END,
      match_status=CASE WHEN reset_regions THEN excluded.match_status ELSE pf_franchise_directory.match_status END,org_id=excluded.org_id,
      version=pf_franchise_directory.version+1,updated_at=now()
    RETURNING id INTO entry_id;
  IF reset_regions THEN
    DELETE FROM pf_franchise_directory_region WHERE record_id=entry_id;
    IF location.region_code IS NOT NULL THEN
      INSERT INTO pf_franchise_directory_region(tenant_id,record_id,region_code)
        VALUES(a.tenant_id,entry_id,location.region_code);
    END IF;
  END IF;
END $$;
CREATE FUNCTION pf_franchise_map_to_directory_sync() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    PERFORM pf_franchise_directory_project(NEW,true);
  ELSE
    PERFORM pf_franchise_directory_project(NEW,
      (NEW.province,NEW.city,NEW.area,NEW.map_district_code) IS DISTINCT FROM (OLD.province,OLD.city,OLD.area,OLD.map_district_code));
  END IF;
  RETURN NEW;
END $$;
-- PostgreSQL executes same-kind triggers by name: the existing map_sync runs first.
CREATE TRIGGER pf_franchise_map_to_directory_sync AFTER INSERT OR UPDATE OF org_name,brand,province,city,area,org_id,account_type,map_district_code
  ON pf_franchise_account FOR EACH ROW EXECUTE FUNCTION pf_franchise_map_to_directory_sync();
SELECT pf_franchise_directory_project(a) FROM pf_franchise_account a WHERE account_type='BOSS';
