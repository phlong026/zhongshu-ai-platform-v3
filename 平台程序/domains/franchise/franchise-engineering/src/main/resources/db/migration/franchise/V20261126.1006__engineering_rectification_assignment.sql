ALTER TABLE fr_eng_rectification ADD COLUMN owner_user_id bigint, ADD COLUMN due_date date;
ALTER TABLE fr_eng_rectification_event ADD COLUMN owner_user_id bigint, ADD COLUMN due_date date;
CREATE INDEX idx_eng_rectification_event_history ON fr_eng_rectification_event(issue_id,create_time,id);
