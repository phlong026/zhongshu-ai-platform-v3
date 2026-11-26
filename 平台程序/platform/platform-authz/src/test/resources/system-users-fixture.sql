CREATE TABLE IF NOT EXISTS system_users (
    id bigserial PRIMARY KEY,
    username varchar(64),
    nickname varchar(64),
    mobile varchar(16),
    dept_id bigint,
    password varchar(100),
    status int2 DEFAULT 0,
    post_ids varchar(255),
    email varchar(64),
    sex int2,
    avatar varchar(512),
    login_ip varchar(64),
    login_date timestamp,
    remark varchar(255),
    creator varchar(64),
    create_time timestamp DEFAULT now(),
    updater varchar(64),
    update_time timestamp DEFAULT now(),
    deleted int2 DEFAULT 0,
    tenant_id bigint DEFAULT 0
);
CREATE SEQUENCE IF NOT EXISTS system_users_seq;
