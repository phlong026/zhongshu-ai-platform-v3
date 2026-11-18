CREATE TABLE pf_map_region (
  code text PRIMARY KEY, province_code text NOT NULL, city_code text NOT NULL,
  province_name text NOT NULL, city_name text NOT NULL, district_name text NOT NULL,
  longitude double precision NOT NULL, latitude double precision NOT NULL
);
CREATE FUNCTION pf_map_name(text) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS
$$ SELECT regexp_replace(btrim($1),'(特别行政区|维吾尔自治区|壮族自治区|回族自治区|自治区|自治州|地区|省|市|区|县)$','') $$;
CREATE INDEX pf_map_region_names ON pf_map_region
  (pf_map_name(province_name),pf_map_name(city_name),pf_map_name(district_name));
CREATE FUNCTION pf_map_matches_city(r pf_map_region, p text, c text) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT pf_map_name(r.province_name)=pf_map_name(p) AND (
    pf_map_name(r.city_name)=pf_map_name(c)
    OR (c IN ('市辖区','县','市辖县') AND r.province_code IN ('110000','120000','310000','500000'))
    OR (c IN ('省直辖县级行政区划','自治区直辖县级行政区划') AND r.city_code=r.code))
$$;
ALTER TABLE pf_franchise_account ADD COLUMN map_district_code text REFERENCES pf_map_region(code);

CREATE TABLE pf_map_brand (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, tenant_id bigint NOT NULL,
  name text NOT NULL, UNIQUE(tenant_id,name), UNIQUE(tenant_id,id)
);
CREATE TABLE pf_store_location (
  account_id varchar(36) PRIMARY KEY REFERENCES pf_franchise_account ON DELETE CASCADE,
  tenant_id bigint NOT NULL, org_id bigint NOT NULL, brand_id bigint NOT NULL,
  region_code text REFERENCES pf_map_region, longitude double precision, latitude double precision,
  CHECK ((longitude IS NULL) = (latitude IS NULL)),
  CHECK (longitude BETWEEN -180 AND 180 AND latitude BETWEEN -90 AND 90),
  FOREIGN KEY(tenant_id,brand_id) REFERENCES pf_map_brand(tenant_id,id)
);
CREATE INDEX pf_store_location_scope ON pf_store_location(tenant_id,brand_id,org_id);
CREATE INDEX pf_store_location_org ON pf_store_location(tenant_id,org_id);

CREATE FUNCTION pf_store_project(a pf_franchise_account) RETURNS void LANGUAGE plpgsql AS $$
DECLARE brand_key bigint; region_key text;
BEGIN
  IF a.account_type <> 'BOSS' OR btrim(a.brand) = '' THEN
    DELETE FROM pf_store_location WHERE account_id=a.account_id;
    RETURN;
  END IF;
  SELECT id INTO brand_key FROM pf_map_brand WHERE tenant_id=a.tenant_id AND name=btrim(a.brand);
  IF brand_key IS NULL THEN
    INSERT INTO pf_map_brand(tenant_id,name) VALUES(a.tenant_id,btrim(a.brand))
      ON CONFLICT(tenant_id,name) DO UPDATE SET name=excluded.name RETURNING id INTO brand_key;
  END IF;
  SELECT CASE WHEN count(*)=1 THEN min(code) END INTO region_key FROM pf_map_region r
    WHERE pf_map_matches_city(r,a.province,a.city)
      AND (CASE WHEN a.map_district_code IS NULL THEN
            pf_map_name(district_name) IN (btrim(a.area),pf_map_name(a.area))
           ELSE code=a.map_district_code END);
  INSERT INTO pf_store_location(account_id,tenant_id,org_id,brand_id,region_code)
    VALUES(a.account_id,a.tenant_id,a.org_id,brand_key,region_key)
    ON CONFLICT(account_id) DO UPDATE SET tenant_id=excluded.tenant_id,org_id=excluded.org_id,
      brand_id=excluded.brand_id,region_code=excluded.region_code,
      longitude=CASE WHEN pf_store_location.region_code=excluded.region_code AND pf_store_location.org_id=excluded.org_id THEN pf_store_location.longitude END,
      latitude=CASE WHEN pf_store_location.region_code=excluded.region_code AND pf_store_location.org_id=excluded.org_id THEN pf_store_location.latitude END;
END $$;
CREATE FUNCTION pf_franchise_map_sync() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM pf_store_project(NEW); RETURN NEW; END $$;
CREATE TRIGGER pf_franchise_map_sync AFTER INSERT OR UPDATE OF brand,province,city,area,org_id,account_type,map_district_code
  ON pf_franchise_account FOR EACH ROW EXECUTE FUNCTION pf_franchise_map_sync();

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,creator,updater)
VALUES(710985,'查看各品牌门店','franchise:store-map:read-all',3,20,710100,'',0,true,'1','1');
INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater)
SELECT 710985000000+row_number() OVER(ORDER BY id),id,710985,tenant_id,'1','1'
FROM system_role WHERE code='boss' AND status=0;
