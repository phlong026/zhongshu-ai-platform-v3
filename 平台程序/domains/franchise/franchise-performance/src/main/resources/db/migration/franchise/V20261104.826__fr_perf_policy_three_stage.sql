-- V20261104.826：M10-T07 提成政策三期化——新增 base_done_cents（基础完成后）；
-- Σ三期=total 由 CHECK 保证（start+base+topping 语义改名 sign_after/base_done/cap_done 列保留存量名）。
-- 修正语义：start_cents=签约后、topping_cents=封顶后（列名沿用存量避免破坏读侧），base_done_cents 新增。
ALTER TABLE fr_perf_commission_policy
    ADD COLUMN IF NOT EXISTS base_done_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fr_perf_commission_policy DROP CONSTRAINT ck_fr_perf_commission_policy_amount;
ALTER TABLE fr_perf_commission_policy ADD CONSTRAINT ck_fr_perf_commission_policy_amount
    CHECK (total_cents > 0 AND total_cents <= 10000000000
        AND start_cents >= 0 AND topping_cents >= 0 AND base_done_cents >= 0
        AND start_cents + topping_cents + base_done_cents = total_cents);
