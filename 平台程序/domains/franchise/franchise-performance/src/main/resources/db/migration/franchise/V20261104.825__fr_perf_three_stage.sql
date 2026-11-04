-- V20261104.825：M10-T07 三期绩效映射——提成 release stage 与工程域 818 枚举对齐
--（SIGN_AFTER 签约后/BASE_DONE 基础完成后/CAP_DONE 封顶后；存量 START→SIGN_AFTER、TOPPING→CAP_DONE）。
ALTER TABLE fr_perf_commission_release DROP CONSTRAINT ck_fr_perf_commission_release_stage;
ALTER TABLE fr_perf_commission_release ADD CONSTRAINT ck_fr_perf_commission_release_stage
    CHECK (stage IN ('START', 'TOPPING', 'SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'));
UPDATE fr_perf_commission_release SET stage = 'SIGN_AFTER' WHERE stage = 'START';
UPDATE fr_perf_commission_release SET stage = 'CAP_DONE' WHERE stage = 'TOPPING';
