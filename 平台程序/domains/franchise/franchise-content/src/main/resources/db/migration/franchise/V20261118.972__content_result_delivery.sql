-- V20261117.961 creates the shared delivery columns, indexes and script uniqueness.
ALTER TABLE fr_content_task ADD CONSTRAINT ck_fr_content_result_attempts CHECK (result_attempts >= 0);

-- Non-text successful tasks do not need a script delivery.
UPDATE fr_content_task SET result_state='READY'
WHERE status='SUCCEEDED' AND kind NOT IN ('copy','asr','link_parse') AND result_state='PENDING';
