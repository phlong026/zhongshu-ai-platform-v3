-- 回滚 V20261104.825：提成三期映射
-- R1 P2-10 预检：启用三期后产生的 BASE_DONE release 行回滚前须先人工导出并确认归属
--（SELECT * FROM fr_perf_commission_release WHERE stage='BASE_DONE'）——恢复两枚举 CHECK 前必须
-- 处理这些行（删除或降级映射回 START/TOPPING 需业务确认，不能静默删除财务关联）。
UPDATE fr_perf_commission_release SET stage = 'START' WHERE stage = 'SIGN_AFTER';
UPDATE fr_perf_commission_release SET stage = 'TOPPING' WHERE stage = 'CAP_DONE';
ALTER TABLE fr_perf_commission_release DROP CONSTRAINT ck_fr_perf_commission_release_stage;
ALTER TABLE fr_perf_commission_release ADD CONSTRAINT ck_fr_perf_commission_release_stage
    CHECK (stage IN ('START', 'TOPPING'));
