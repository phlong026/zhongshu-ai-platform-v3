-- V20261104.822：M06-T03 冻结移交+统一合同关联——移交包提交时冻结当时有效下发方案版本
-- （frozen_scheme_version；后续变更留版本不覆盖），供工程档案与联验核对「接收的是哪一版」。
ALTER TABLE fr_commission_handover
    ADD COLUMN IF NOT EXISTS frozen_scheme_version VARCHAR(64) NULL;
