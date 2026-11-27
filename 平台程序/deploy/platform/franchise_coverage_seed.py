"""Build the insert-only transaction for a private B coverage seed."""
import base64
import hashlib
import json
import re


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _code(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9]{6}", value) is not None


def seed_sql(path, expected_sha, tenant, repository):
    _require(type(tenant) is int and 0 < tenant < 2**63, "Specify the target coverage tenant ID")
    _require(path.is_absolute() and path.is_file() and not path.is_symlink()
             and not path.resolve().is_relative_to(repository) and not (path.stat().st_mode & 0o077),
             "Use an absolute, private coverage seed file outside the release tree (mode 0600)")
    with path.open("rb") as stream:
        data = stream.read(1024 * 1024 + 1)
    _require(len(data) <= 1024 * 1024 and hashlib.sha256(data).hexdigest() == expected_sha,
             "Coverage seed file size or SHA-256 mismatch")
    try:
        payload = json.loads(data)
    except (ValueError, UnicodeError):
        raise ValueError("Invalid coverage seed JSON") from None
    _require(isinstance(payload, dict) and set(payload) == {"workbook_sha256", "rows"}
             and re.fullmatch(r"[0-9a-f]{64}", str(payload["workbook_sha256"])),
             "Invalid coverage seed envelope")
    rows = payload["rows"]
    _require(isinstance(rows, list) and 0 < len(rows) <= 2000, "Coverage seed expects 1–2000 rows")
    seen = set()
    for row in rows:
        _require(isinstance(row, dict) and set(row) == {
            "sheet", "cell", "brand", "name", "region", "province", "city", "codes", "status", "fingerprint",
        }, "Unexpected coverage seed fields")
        for key, limit in {"cell": 12, "brand": 40, "name": 128, "region": 500}.items():
            value = row[key]
            _require(isinstance(value, str) and 0 < len(value) <= limit
                     and value == value.strip() and not any(ord(c) < 32 for c in value),
                     "Invalid coverage seed text")
        _require(type(row["sheet"]) is int and row["sheet"] == 1
                 and re.fullmatch(r"[A-Z]{1,3}[1-9][0-9]{0,6}", row["cell"]),
                 "Invalid coverage source cell")
        _require(row["cell"] not in seen, "Duplicate coverage source cell")
        seen.add(row["cell"])
        codes = row["codes"]
        _require(isinstance(codes, list) and len(codes) <= 100 and all(map(_code, codes))
                 and len(set(codes)) == len(codes) and all(v is None or _code(v) for v in (row["province"], row["city"])),
                 "Invalid coverage region codes")
        _require(row["status"] in ("RESOLVED", "PARTIAL", "UNRESOLVED")
                 and re.fullmatch(r"[0-9a-f]{64}", str(row["fingerprint"])), "Invalid coverage resolution")
    # Base64 keeps file contents out of psql syntax, including backslash commands.
    encoded = base64.b64encode(data).decode("ascii")
    return f"""
BEGIN;
SET LOCAL search_path TO pg_catalog, public;
CREATE TEMP TABLE coverage_seed ON COMMIT DROP AS
SELECT row.*, 'xlsx:' || (source.doc->>'workbook_sha256') || ':s' || row.sheet || ':' || row.cell AS source_key
FROM (SELECT convert_from(decode('{encoded}', 'base64'), 'UTF8')::jsonb AS doc) source
CROSS JOIN LATERAL jsonb_to_recordset(source.doc->'rows') AS row(
  sheet integer, cell text, brand text, name text, region text, province text, city text,
  codes text[], status text, fingerprint text);
DO $validate$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.system_tenant WHERE id={tenant} AND status=0 AND deleted=0) THEN
    RAISE EXCEPTION 'Coverage seed target tenant is absent or disabled';
  END IF;
  IF EXISTS (
    WITH catalog AS (
      SELECT code FROM public.pf_map_region UNION SELECT province_code FROM public.pf_map_region
      UNION SELECT city_code FROM public.pf_map_region
    )
    SELECT 1 FROM pg_temp.coverage_seed seed
    CROSS JOIN LATERAL unnest(seed.codes || ARRAY[seed.province, seed.city]) AS scope(code)
    WHERE scope.code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM catalog c WHERE c.code=scope.code)
  ) THEN
    RAISE EXCEPTION 'Coverage seed refers to a code outside the installed administrative catalog';
  END IF;
END
$validate$;
INSERT INTO public.pf_map_brand(tenant_id,name)
SELECT DISTINCT {tenant},seed.brand FROM pg_temp.coverage_seed seed
WHERE NOT EXISTS (SELECT 1 FROM public.pf_franchise_directory d WHERE d.tenant_id={tenant} AND d.source_key=seed.source_key)
ON CONFLICT(tenant_id,name) DO NOTHING;
WITH created AS (
  INSERT INTO public.pf_franchise_directory(
    id,tenant_id,brand_id,name,record_type,original_region_text,match_status,
    province_code,city_code,source_key,source_fingerprint,source_row,source_sheet,source_cell,whole_area_coverage)
  SELECT gen_random_uuid()::text,{tenant},b.id,seed.name,'COVERAGE',seed.region,seed.status,
    seed.province,seed.city,seed.source_key,seed.fingerprint,
    substring(seed.cell from '[0-9]+')::integer,'Sheet1',seed.cell,false
  FROM pg_temp.coverage_seed seed
  JOIN public.pf_map_brand b ON b.tenant_id={tenant} AND b.name=seed.brand
  ON CONFLICT(tenant_id,source_key) DO NOTHING
  RETURNING id,source_key
), regions AS (
  INSERT INTO public.pf_franchise_directory_region(tenant_id,record_id,region_code)
  SELECT {tenant},c.id,unnest(seed.codes) FROM created c JOIN pg_temp.coverage_seed seed USING(source_key)
  RETURNING record_id
)
SELECT json_build_object('created',(SELECT count(*) FROM created),
  'skipped',(SELECT count(*) FROM pg_temp.coverage_seed)-(SELECT count(*) FROM created),
  'region_links',(SELECT count(*) FROM regions));
COMMIT;
"""
