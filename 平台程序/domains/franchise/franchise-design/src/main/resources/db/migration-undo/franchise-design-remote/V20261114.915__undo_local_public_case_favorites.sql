-- B-only empty-table rollback. Never connect this undo to A's database or Flyway locations.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15s';
DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'U915_REQUIRES_READ_COMMITTED: use a fresh B owner connection';
    END IF;
    LOCK TABLE fr_design_case_favorite IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_design_case_favorite) THEN
        RAISE EXCEPTION 'U915_FAVORITES_EXIST: preserve employee favorites and roll back application only';
    END IF;
END $$;
DROP TABLE fr_design_case_favorite RESTRICT;
COMMIT;
