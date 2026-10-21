-- 有记录时拒绝自动删表：先按保留策略导出/处置追踪数据，再清空并执行回滚。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM ai_runtime_call_trace LIMIT 1)
        OR EXISTS (SELECT 1 FROM ai_job_result WHERE source_content_sha256 IS NOT NULL
            OR pixel_width IS NOT NULL OR pixel_height IS NOT NULL LIMIT 1) THEN
        RAISE EXCEPTION 'RUNTIME_TRACE_ROLLBACK_REQUIRES_DATA_DISPOSITION';
    END IF;
END $$;
ALTER TABLE ai_job_result DROP CONSTRAINT ck_ai_result_scan_pixels,
    DROP COLUMN source_content_sha256, DROP COLUMN pixel_width, DROP COLUMN pixel_height;
DROP TABLE ai_runtime_call_trace;
