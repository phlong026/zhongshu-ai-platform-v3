-- Current and historical snapshots both retain a customer's private file ownership after an attachment is replaced.
CREATE INDEX fr_customer_workflow_step_assets ON fr_customer_workflow_step USING gin(attachments jsonb_path_ops);
CREATE INDEX fr_customer_workflow_event_assets ON fr_customer_workflow_event USING gin(attachments jsonb_path_ops);
