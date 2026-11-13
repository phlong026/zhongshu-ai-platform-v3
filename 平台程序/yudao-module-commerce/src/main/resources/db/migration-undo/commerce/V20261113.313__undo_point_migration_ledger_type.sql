-- V20261113.313 回滚：移除 MIGRATION_OUT 类型（存在迁移流水时拒绝回滚，避免破坏审计）
DO $$
BEGIN
  IF to_regclass('public.design_point_ledger') IS NOT NULL
     AND EXISTS (SELECT 1 FROM design_point_ledger WHERE type = 'MIGRATION_OUT') THEN
    RAISE EXCEPTION '存在 MIGRATION_OUT 迁移流水，禁止回滚（请先归档或人工确认）';
  END IF;
END $$;
ALTER TABLE design_point_ledger DROP CONSTRAINT ck_design_point_ledger_type;
ALTER TABLE design_point_ledger ADD CONSTRAINT ck_design_point_ledger_type CHECK (type IN
    ('RECHARGE_BASE_CREDIT', 'RECHARGE_BONUS_CREDIT', 'FLAT_GENERATION_DEBIT',
     'ELEVATION_GENERATION_DEBIT', 'TASK_SETTLEMENT_REFUND', 'MANUAL_CREDIT', 'MANUAL_DEBIT'));
