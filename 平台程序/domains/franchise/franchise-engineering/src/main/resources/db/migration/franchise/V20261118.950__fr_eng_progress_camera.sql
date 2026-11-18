ALTER TABLE fr_eng_plan_node DROP CONSTRAINT ck_fr_eng_plan_node_milestone;
ALTER TABLE fr_eng_plan_node ADD CONSTRAINT ck_fr_eng_plan_node_milestone CHECK (milestone IS NULL OR milestone IN ('START','TOPPING'));
ALTER TABLE fr_eng_plan_node ADD COLUMN baseline_end_date DATE;
ALTER TABLE fr_eng_plan_node ADD COLUMN delay_reason VARCHAR(1000);
ALTER TABLE fr_eng_plan_node ADD COLUMN delay_owner_user_id BIGINT;
ALTER TABLE fr_eng_plan_node ADD COLUMN recovery_date DATE;
ALTER TABLE fr_eng_plan_node ADD COLUMN recovery_action VARCHAR(1000);
ALTER TABLE fr_eng_plan_node ADD COLUMN delay_history JSONB NOT NULL DEFAULT '[]';
UPDATE fr_eng_plan_node SET baseline_end_date=end_date;
-- Existing open projects keep their node IDs, dates and completion history.
INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,start_date,end_date,baseline_end_date,status,milestone,completed_at,responsible_user_id)
SELECT -p.id,p.company_id,p.id,0,'正式开工',COALESCE(n.start_date,p.actual_start_at),COALESCE(n.start_date,p.actual_start_at),COALESCE(n.start_date,p.actual_start_at),
 CASE WHEN p.actual_start_at IS NULL THEN 'PENDING' ELSE 'DONE' END,'START',p.actual_start_at,
 CASE WHEN p.actual_start_at IS NULL THEN NULL ELSE p.manager_user_id END
FROM fr_eng_project p LEFT JOIN LATERAL (SELECT start_date FROM fr_eng_plan_node WHERE project_id=p.id ORDER BY node_index LIMIT 1) n ON TRUE
WHERE p.status<>'ARCHIVED' AND NOT EXISTS(SELECT 1 FROM fr_eng_plan_node WHERE project_id=p.id AND milestone='START');

CREATE TABLE fr_eng_camera (
 id BIGINT PRIMARY KEY, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL, location VARCHAR(120) NOT NULL,
 stream_url VARCHAR(2048) NOT NULL DEFAULT '', enabled BOOLEAN NOT NULL DEFAULT TRUE,
 author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fr_eng_camera_project ON fr_eng_camera(company_id,project_id);
COMMENT ON COLUMN fr_eng_camera.stream_url IS 'Restricted playback URL. Never included in camera lists or command logs.';
-- Deployments use separate schema-owner and application roles. Inherit only the
-- existing engineering project's CRUD grants, without granting unrelated roles access.
DO $$ DECLARE g RECORD; BEGIN
 FOR g IN SELECT grantee,string_agg(privilege_type,',') AS privileges
          FROM information_schema.role_table_grants
          WHERE table_schema=current_schema() AND table_name='fr_eng_project'
            AND privilege_type IN ('SELECT','INSERT','UPDATE','DELETE') AND grantee<>current_user
          GROUP BY grantee
 LOOP EXECUTE format('GRANT %s ON TABLE fr_eng_camera TO %I',g.privileges,g.grantee); END LOOP;
END $$;
