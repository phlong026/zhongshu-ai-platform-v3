-- V20261104.823：M06-T03/R2——下发冻结实际使用的提交版本引用。
-- dispatch_seq=下发次数、submission.version=委托重提次数，两者不等价（费用退回重提至 V2 后
-- 首次下发 seq 仍为 1）——移交包按「下发冻结的提交版本」取参数，不得按 seq 猜。
-- 存量回填近似=dispatch_seq（差异仅存在于费用退回重提后下发的行，登记执行记录由联验核对）。
ALTER TABLE fr_commission
    ADD COLUMN IF NOT EXISTS dispatched_submission_version INT NULL;
UPDATE fr_commission SET dispatched_submission_version = dispatch_seq
    WHERE dispatched_submission_version IS NULL AND dispatch_seq IS NOT NULL;
