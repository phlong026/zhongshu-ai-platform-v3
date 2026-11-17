ALTER TABLE fr_content_task ADD COLUMN result_state VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE fr_content_task ADD COLUMN result_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE fr_content_task ADD COLUMN result_retry_after TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE fr_content_task ADD COLUMN result_error VARCHAR(500);
ALTER TABLE fr_content_task ADD CONSTRAINT ck_content_result_state CHECK (result_state IN ('PENDING','READY','FAILED'));
-- Preserve already delivered scripts. Missing results remain pending for the recovery sweep.
UPDATE fr_content_task t SET result_state='READY' WHERE t.status='SUCCEEDED' AND EXISTS (
 SELECT 1 FROM fr_content_script s WHERE s.task_id=t.id AND s.company_id=t.company_id
 AND CASE WHEN t.kind='copy' THEN NULLIF(btrim(s.optimized_text),'') IS NOT NULL
 ELSE NULLIF(btrim(s.raw_text),'') IS NOT NULL END);
-- Refuse ambiguous historical duplicates instead of silently deleting business data.
CREATE UNIQUE INDEX uk_content_script_task ON fr_content_script(task_id) WHERE task_id IS NOT NULL;
CREATE INDEX idx_content_result_pending ON fr_content_task(result_retry_after,id)
 WHERE status='SUCCEEDED' AND result_state='PENDING' AND kind IN ('copy','asr','link_parse');
