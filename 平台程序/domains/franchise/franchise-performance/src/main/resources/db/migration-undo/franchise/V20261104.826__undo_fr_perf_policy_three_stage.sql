-- 回滚 V20261104.826：提成政策三期列
ALTER TABLE fr_perf_commission_policy DROP CONSTRAINT ck_fr_perf_commission_policy_amount;
ALTER TABLE fr_perf_commission_policy ADD CONSTRAINT ck_fr_perf_commission_policy_amount
    CHECK (total_cents > 0 AND total_cents <= 10000000000
        AND start_cents >= 0 AND topping_cents >= 0 AND start_cents + topping_cents = total_cents);
ALTER TABLE fr_perf_commission_policy DROP COLUMN IF EXISTS base_done_cents;
