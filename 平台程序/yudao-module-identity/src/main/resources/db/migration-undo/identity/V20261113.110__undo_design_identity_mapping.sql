-- V20261113.110 回滚：移除旧设计账号身份映射
-- P2④：存在人工处置结论（CONFIRMED/REJECTED）时拒绝 DROP，避免丢失审计与迁移依据。
DO $$
BEGIN
  IF to_regclass('public.design_identity_mapping') IS NOT NULL THEN
    IF EXISTS (SELECT 1 FROM design_identity_mapping WHERE status IN ('CONFIRMED','REJECTED')) THEN
      RAISE EXCEPTION '存在已确认/已驳回的身份映射，禁止回滚删除；请先导出归档或人工确认';
    END IF;
  END IF;
END $$;
DROP INDEX IF EXISTS idx_dim_status;
DROP INDEX IF EXISTS idx_dim_org;
DROP TABLE IF EXISTS design_identity_mapping;
