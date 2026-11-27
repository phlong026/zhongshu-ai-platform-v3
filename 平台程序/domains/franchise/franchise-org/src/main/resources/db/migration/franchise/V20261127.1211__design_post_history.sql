-- 1200 已发布，不改其 checksum。Flyway callback 在首次执行 1200 前复用本脚本；
-- 本版本随后记录相同的幂等收敛。保留任职历史，统一使用既有 REVOKED 状态。
-- NOT VALID 只免除已存在历史行的回扫；新任职和 role_code 变更仍受原外键约束。
DO $$
DECLARE
    role_fk pg_constraint%ROWTYPE;
BEGIN
    IF to_regclass('pf_staff_post') IS NULL OR to_regclass('pf_post_template') IS NULL THEN
        RAISE EXCEPTION 'Design retirement requires the existing platform post schema';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pf_staff_post
                   WHERE role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin')) THEN
        RETURN;
    END IF;
    PERFORM set_config('lock_timeout', '10s', true);
    PERFORM set_config('statement_timeout', '60s', true);
    LOCK TABLE pf_post_template, pf_staff_post IN ACCESS EXCLUSIVE MODE;
    SELECT * INTO role_fk FROM pg_constraint
    WHERE conrelid = 'pf_staff_post'::regclass AND conname = 'fk_pf_staff_post_role';
    IF role_fk.oid IS NULL OR role_fk.contype <> 'f'
       OR role_fk.confrelid <> 'pf_post_template'::regclass
       OR role_fk.conkey <> ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = 'pf_staff_post'::regclass AND attname = 'role_code')]
       OR role_fk.confkey <> ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = 'pf_post_template'::regclass AND attname = 'role_code')]
       OR role_fk.confupdtype <> 'a' OR role_fk.confdeltype <> 'a'
       OR role_fk.confmatchtype <> 's' OR role_fk.condeferrable OR role_fk.condeferred
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
                      WHERE conrelid = 'pf_staff_post'::regclass AND conname = 'ck_pf_staff_post_status'
                        AND convalidated AND pg_get_constraintdef(oid) =
                        'CHECK (((status)::text = ANY ((ARRAY[''ACTIVE''::character varying, ''REVOKED''::character varying])::text[])))')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
                      WHERE conrelid = 'pf_staff_post'::regclass AND conname = 'ck_pf_staff_post_revoked'
                        AND convalidated AND pg_get_constraintdef(oid) =
                        'CHECK ((((status)::text = ''REVOKED''::text) = (revoked_at IS NOT NULL)))') THEN
        RAISE EXCEPTION 'Unknown post constraints; verify the original schema before retirement';
    END IF;
    IF NOT role_fk.convalidated
       AND NOT EXISTS (SELECT 1 FROM pf_staff_post WHERE status = 'ACTIVE'
                       AND role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin'))
       AND NOT EXISTS (SELECT 1 FROM pf_post_template WHERE domain = 'franchise'
                       AND role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin')) THEN
        RETURN;
    END IF;
    UPDATE pf_staff_post SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP, revoked_by = 0
    WHERE status = 'ACTIVE' AND role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin');
    ALTER TABLE pf_staff_post DROP CONSTRAINT fk_pf_staff_post_role;
    DELETE FROM pf_post_template
    WHERE domain = 'franchise' AND role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin');
    ALTER TABLE pf_staff_post ADD CONSTRAINT fk_pf_staff_post_role
        FOREIGN KEY (role_code) REFERENCES pf_post_template (role_code) NOT VALID;
END $$;
