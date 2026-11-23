-- Keep the confirmation facts separate from later ledger changes. Existing confirmations stay readable.
ALTER TABLE fr_perf_reconciliation ADD COLUMN source_snapshot TEXT;
