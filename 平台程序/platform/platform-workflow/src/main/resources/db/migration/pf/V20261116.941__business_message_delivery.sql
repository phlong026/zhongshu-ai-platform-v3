-- Activation is persisted once: restarts must not drop in-flight task notifications,
-- and enabling delivery must not broadcast historical tasks to existing users.
CREATE TABLE pf_business_message_activation (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO pf_business_message_activation (id) VALUES (1);
ALTER TABLE pf_message DROP CONSTRAINT ck_pf_message_route;
ALTER TABLE pf_message ADD CONSTRAINT ck_pf_message_route CHECK (route IN (
    'customer', 'course', 'personal-performance', 'privacy',
    'design-dispatch', 'design-progress', 'commission', 'content-task'
));
