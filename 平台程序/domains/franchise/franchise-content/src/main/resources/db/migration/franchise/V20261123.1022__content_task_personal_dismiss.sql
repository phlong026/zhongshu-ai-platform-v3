-- Personal task-center visibility only. The underlying generation task remains immutable and billable state is untouched.
CREATE TABLE fr_content_task_hidden (
    company_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    actor_post_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, author_user_id, task_id),
    CONSTRAINT fk_fr_content_task_hidden_task
        FOREIGN KEY (task_id) REFERENCES fr_content_task(id) ON DELETE CASCADE
);
CREATE INDEX idx_fr_content_task_hidden_owner
    ON fr_content_task_hidden(company_id, author_user_id, create_time DESC);
