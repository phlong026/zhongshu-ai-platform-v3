-- V20261113.314 prompt 调用收据（24 号 D6 §2.3-B）：callId/hash 持久化，防跨 attempt 重投与收据冲突
CREATE TABLE design_prompt_receipt (
  id            BIGINT PRIMARY KEY,
  job_id        BIGINT      NOT NULL,
  attempt_no    INT         NOT NULL,
  call_id       VARCHAR(64),
  response_hash VARCHAR(64),
  outcome       VARCHAR(16) NOT NULL,
  operation_id  BIGINT,
  create_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
  update_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_dpr_attempt UNIQUE (job_id, attempt_no),
  CONSTRAINT ck_dpr_outcome CHECK (outcome IN ('DISPATCHED','SUCCEEDED','UNKNOWN','NOT_SENT'))
);
CREATE INDEX idx_dpr_job ON design_prompt_receipt (job_id);
