-- 访问日志字段扩容（2026-10-20）
-- 背景：接口 @Operation(summary) 可能超过 50 字符（缩略图端点摘要 56 字），
-- 异步写 infra_api_access_log 时 operate_name varchar(50) 溢出，整条访问日志写入失败并打印 ERROR。
-- 处理：只扩长、不缩短、不改历史数据；表不存在时安全空转（仅加载业务迁移基线的测试环境）。
DO $$
BEGIN
  IF to_regclass('public.infra_api_access_log') IS NULL THEN
    RAISE NOTICE 'infra_api_access_log 不存在，跳过访问日志字段扩容';
    RETURN;
  END IF;

  ALTER TABLE public.infra_api_access_log
    ALTER COLUMN operate_name TYPE VARCHAR(255),
    ALTER COLUMN operate_module TYPE VARCHAR(255),
    ALTER COLUMN application_name TYPE VARCHAR(100);
END $$;
