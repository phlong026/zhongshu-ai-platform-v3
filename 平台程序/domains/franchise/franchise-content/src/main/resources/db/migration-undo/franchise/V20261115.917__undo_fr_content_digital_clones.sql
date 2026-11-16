-- 仅允许回滚尚未产生任何制作或积分事实的空表；已失败资源也保留授权与扣点审计。
DO $$
DECLARE has_pending_ledger BOOLEAN := FALSE;
BEGIN
    IF to_regclass('public.fr_content_digital_clone') IS NULL THEN RETURN; END IF;
    LOCK TABLE fr_content_digital_clone IN ACCESS EXCLUSIVE MODE;
    IF to_regclass('public.org_point_ledger') IS NOT NULL THEN
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM org_point_ledger r '
             || 'WHERE r.type = ''RESERVE'' AND r.biz_key LIKE ''content-clone:%'' '
             || 'AND NOT EXISTS (SELECT 1 FROM org_point_ledger x '
             || 'WHERE x.operation_id = r.operation_id AND x.type IN (''SETTLE'',''RELEASE'')))'
        INTO has_pending_ledger;
    END IF;
    IF EXISTS (SELECT 1 FROM fr_content_digital_clone) OR has_pending_ledger THEN
        RAISE EXCEPTION '数字人制作或积分事实已存在，禁止回滚917';
    END IF;
    DROP TABLE fr_content_digital_clone;
END $$;
