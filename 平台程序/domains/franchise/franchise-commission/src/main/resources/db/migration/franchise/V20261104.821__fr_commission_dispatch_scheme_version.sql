-- V20261104.821：M05-T04 确认下发版本快照——fr_commission 加 dispatched_scheme_version
ALTER TABLE fr_commission ADD COLUMN dispatched_scheme_version VARCHAR(64) NULL;
COMMENT ON COLUMN fr_commission.dispatched_scheme_version IS 'M05-T04:确认下发时冻结的设计方案版本快照（P{plan}/B{budget}/F{facade}）';
