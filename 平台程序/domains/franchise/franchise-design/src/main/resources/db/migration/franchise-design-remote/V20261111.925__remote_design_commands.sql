-- B only. Do not add this location to A's default Flyway locations.
CREATE TABLE fr_design_remote_command (
    command_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    caller_app_id varchar(80) NOT NULL,
    org_id bigint NOT NULL CHECK (org_id > 0),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    remote_request_key varchar(80) NOT NULL DEFAULT gen_random_uuid()::text UNIQUE,
    operation varchar(64) NOT NULL,
    request_key varchar(200) NOT NULL,
    fingerprint char(64) NOT NULL,
    actor_json jsonb NOT NULL,
    payload_json jsonb NOT NULL,
    price_snapshot jsonb,
    state varchar(16) NOT NULL CHECK (state IN ('NEW','SENT','UNKNOWN','SUCCEEDED','REJECTED')),
    response_json jsonb,
    receipt_id varchar(36),
    job_id bigint,
    error_code integer,
    error_message text,
    audited boolean NOT NULL DEFAULT false,
    settlement_version bigint NOT NULL DEFAULT 0 CHECK (settlement_version >= 0),
    settlement_json jsonb,
    settled_terminal boolean NOT NULL DEFAULT false,
    lease_token varchar(36),
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (caller_app_id, tenant_id, org_id, operation, request_key)
);
CREATE INDEX fr_design_remote_recovery ON fr_design_remote_command(next_attempt_at, command_id)
    WHERE state IN ('NEW','SENT','UNKNOWN')
       OR (state='SUCCEEDED' AND job_id IS NOT NULL AND price_snapshot IS NOT NULL AND settled_terminal=false);
CREATE INDEX fr_design_remote_job ON fr_design_remote_command(caller_app_id,tenant_id,org_id,job_id) WHERE job_id IS NOT NULL;
-- Rollback only after draining UNKNOWN and pending settlement: drop fr_design_remote_command.
