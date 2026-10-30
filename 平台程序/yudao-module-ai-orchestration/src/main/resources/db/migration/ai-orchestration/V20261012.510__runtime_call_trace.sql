-- T26-08：独立调用追踪；不修改既有提示词计费事件、扣点账本或 response_hash。
-- request_spec 保存实际构造请求的文本与受控元数据，不保存鉴权头、签名 URL 或图片 base64。
CREATE TABLE ai_runtime_call_trace (
    id BIGINT NOT NULL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    call_id VARCHAR(96) NOT NULL,
    source_attempt_no INT NOT NULL,
    source_fencing_token BIGINT NOT NULL,
    stage VARCHAR(16) NOT NULL,
    candidate_slot_no INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    request_spec JSONB NOT NULL,
    response_spec JSONB NULL,
    error_code VARCHAR(64) NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_runtime_trace_call UNIQUE (job_id, call_id),
    CONSTRAINT ck_runtime_trace_attempt CHECK (source_attempt_no > 0 AND source_fencing_token > 0),
    CONSTRAINT ck_runtime_trace_stage CHECK ((stage = 'PLAN' AND candidate_slot_no = 0)
        OR (stage = 'GENERATE' AND candidate_slot_no BETWEEN 1 AND 4)),
    CONSTRAINT ck_runtime_trace_state CHECK (state IN ('REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'UNKNOWN', 'NOT_SENT')),
    CONSTRAINT ck_runtime_trace_request CHECK (jsonb_typeof(request_spec) = 'object'
        AND octet_length(request_spec::text) <= 524288),
    CONSTRAINT ck_runtime_trace_response CHECK (response_spec IS NULL OR
        (jsonb_typeof(response_spec) = 'object' AND octet_length(response_spec::text) <= 65536))
);
COMMENT ON TABLE ai_runtime_call_trace IS '受限后台详情读取；用户原始需求的衍生数据，账号注销时删除。不是计费凭证。';

-- Reuse the existing Core scan, retaining its dimensions through candidate promotion.
-- The original supplier digest remains distinct from the existing sanitized content_sha256.
ALTER TABLE ai_job_result
    ADD COLUMN source_content_sha256 VARCHAR(64),
    ADD COLUMN pixel_width INT,
    ADD COLUMN pixel_height INT,
    ADD CONSTRAINT ck_ai_result_scan_pixels CHECK (
        (pixel_width IS NULL AND pixel_height IS NULL)
        OR (pixel_width IS NOT NULL AND pixel_height IS NOT NULL AND pixel_width > 0 AND pixel_height > 0));
COMMENT ON COLUMN ai_job_result.source_content_sha256 IS 'Core 已验证的原始输出摘要；与净化后 content_sha256 分开，历史未知不回填。';
COMMENT ON COLUMN ai_job_result.pixel_width IS '既有内容扫描读取的实际像素宽度，不从请求档位推算。';
COMMENT ON COLUMN ai_job_result.pixel_height IS '既有内容扫描读取的实际像素高度，不从请求档位推算。';
