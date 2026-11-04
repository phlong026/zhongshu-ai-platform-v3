ALTER TABLE fr_commission DROP COLUMN IF EXISTS dispatched_scheme_version;
DELETE FROM flyway_schema_history WHERE version = '20261104.821' AND description LIKE '%dispatch_scheme_version%';
