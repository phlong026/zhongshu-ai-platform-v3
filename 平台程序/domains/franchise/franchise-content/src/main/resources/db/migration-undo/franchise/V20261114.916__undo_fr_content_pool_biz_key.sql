-- 资金事实不可因 schema 回滚丢失；锁表后再检查，关闭检查到删列之间的并发写窗口。
LOCK TABLE fr_content_task IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    has_pending_ledger BOOLEAN := FALSE;
BEGIN
    IF to_regclass('public.org_point_ledger') IS NOT NULL THEN
        EXECUTE 'SELECT EXISTS ('
             || 'SELECT 1 FROM org_point_ledger r '
             || 'WHERE r.type = ''RESERVE'' AND r.biz_key LIKE ''content:%'' AND NOT EXISTS ('
             || 'SELECT 1 FROM org_point_ledger x WHERE x.operation_id = r.operation_id '
             || 'AND x.type IN (''SETTLE'',''RELEASE'')))'
        INTO has_pending_ledger;
    END IF;
    IF EXISTS (SELECT 1 FROM fr_content_task WHERE pool_biz_key IS NOT NULL) OR has_pending_ledger THEN
        RAISE EXCEPTION 'fr_content_task 存在积分池资金事实，禁止回滚 916';
    END IF;
END $$;

DELETE FROM system_role_menu WHERE menu_id = 710504;
DELETE FROM system_menu WHERE id = 710504 AND permission = 'franchise:content:verify';

DROP INDEX IF EXISTS uk_fct_manual_verify_idem;
DROP INDEX IF EXISTS idx_fct_pool_reconcile;
DROP INDEX IF EXISTS idx_fct_pool_biz_key;
ALTER TABLE fr_content_task
    DROP COLUMN IF EXISTS manual_verified_at,
    DROP COLUMN IF EXISTS manual_verify_idempotency_key,
    DROP COLUMN IF EXISTS manual_verify_operator_post_id,
    DROP COLUMN IF EXISTS manual_verify_operator_user_id,
    DROP COLUMN IF EXISTS manual_verify_evidence,
    DROP COLUMN IF EXISTS manual_verify_reason,
    DROP COLUMN IF EXISTS manual_verify_actual_units,
    DROP COLUMN IF EXISTS manual_verify_decision,
    DROP COLUMN IF EXISTS pool_reconcile_lease_until,
    DROP COLUMN IF EXISTS pool_reconcile_lease_token,
    DROP COLUMN IF EXISTS pool_reconcile_error,
    DROP COLUMN IF EXISTS pool_reconcile_after,
    DROP COLUMN IF EXISTS pool_reconcile_attempts,
    DROP COLUMN IF EXISTS poll_lease_until,
    DROP COLUMN IF EXISTS poll_lease_token,
    DROP COLUMN IF EXISTS dispatch_started_at,
    DROP COLUMN IF EXISTS pool_terminal_intent,
    DROP COLUMN IF EXISTS pool_reserved_units,
    DROP COLUMN IF EXISTS pool_biz_key;
