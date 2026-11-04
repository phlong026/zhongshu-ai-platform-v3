-- 回滚 V20261104.825：提成三期映射
UPDATE fr_perf_commission_release SET stage = 'START' WHERE stage = 'SIGN_AFTER';
UPDATE fr_perf_commission_release SET stage = 'TOPPING' WHERE stage = 'CAP_DONE';
ALTER TABLE fr_perf_commission_release DROP CONSTRAINT ck_fr_perf_commission_release_stage;
ALTER TABLE fr_perf_commission_release ADD CONSTRAINT ck_fr_perf_commission_release_stage
    CHECK (stage IN ('START', 'TOPPING'));
