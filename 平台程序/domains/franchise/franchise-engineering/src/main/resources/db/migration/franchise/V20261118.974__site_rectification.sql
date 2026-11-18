CREATE TABLE fr_eng_rectification (
    id bigint PRIMARY KEY, company_id bigint NOT NULL, project_id bigint NOT NULL REFERENCES fr_eng_project(id),
    node_id bigint REFERENCES fr_eng_plan_node(id), title varchar(160) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('OPEN','REVIEW','CLOSED')),
    created_by bigint NOT NULL, create_time timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_eng_rectification_active ON fr_eng_rectification(company_id,project_id,state);
CREATE TABLE fr_eng_rectification_event (
    id bigint PRIMARY KEY, issue_id bigint NOT NULL REFERENCES fr_eng_rectification(id), actor_id bigint NOT NULL,
    action varchar(16) NOT NULL, note varchar(800) NOT NULL, create_time timestamptz NOT NULL DEFAULT now()
);
