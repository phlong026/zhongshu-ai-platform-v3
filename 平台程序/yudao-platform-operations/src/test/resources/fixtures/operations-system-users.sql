CREATE TABLE IF NOT EXISTS system_users (
    id bigint PRIMARY KEY,
    tenant_id bigint,
    nickname text,
    mobile text,
    username text,
    password text,
    status int DEFAULT 0,
    deleted int2 DEFAULT 0,
    update_time timestamptz
);
