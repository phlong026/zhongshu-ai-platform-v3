-- 首页接待与预算确认：历史普通跟进不得追认为实际接待。
ALTER TABLE fr_customer_follow ADD COLUMN contact_kind varchar(16) NOT NULL DEFAULT 'FOLLOW' CHECK(contact_kind IN ('FOLLOW','VISIT'));
ALTER TABLE fr_customer_follow ADD COLUMN occurred_on date;
ALTER TABLE fr_customer_scheme ADD COLUMN confirmed_at timestamptz;
ALTER TABLE fr_customer_scheme ADD COLUMN confirmed_by_user_id bigint;
ALTER TABLE fr_customer_scheme ADD COLUMN confirmation_note varchar(500);
CREATE INDEX idx_fr_customer_visit_month ON fr_customer_follow(company_id,occurred_on,customer_id) WHERE contact_kind='VISIT';
