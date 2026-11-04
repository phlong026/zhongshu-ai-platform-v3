-- 回滚 V20261104.826：提成政策三期列
-- R1 P2-10 预检：存在正数 base_done_cents 政策行时恢复二分 CHECK 会失败——回滚前须人工确认
-- （SELECT * FROM fr_perf_commission_policy WHERE base_done_cents > 0），不能静默删除三期数据。
ALTER TABLE fr_perf_commission_policy DROP CONSTRAINT ck_fr_perf_commission_policy_amounts;
ALTER TABLE fr_perf_commission_policy ADD CONSTRAINT ck_fr_perf_commission_policy_amounts
    CHECK (total_cents > 0 AND total_cents <= 10000000000
        AND start_cents >= 0 AND topping_cents >= 0 AND start_cents + topping_cents = total_cents);
ALTER TABLE fr_perf_commission_policy DROP COLUMN IF EXISTS base_done_cents;
