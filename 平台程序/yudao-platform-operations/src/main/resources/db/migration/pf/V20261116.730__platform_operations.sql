CREATE TABLE pf_org_profile (
 tenant_id bigint NOT NULL, org_id bigint NOT NULL REFERENCES pf_org_unit(dept_id),
 contact_name varchar(40), contact_mobile varchar(20), address varchar(300),
 version bigint NOT NULL DEFAULT 0, updated_by bigint, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,org_id)
);
CREATE TABLE pf_org_capability_policy (
 tenant_id bigint NOT NULL, org_id bigint NOT NULL REFERENCES pf_org_unit(dept_id),
 allowed_subjects jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(allowed_subjects)='array'),
 version bigint NOT NULL DEFAULT 1, updated_by bigint, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,org_id)
);
-- 冻结既有组织当前能力；新组织无行即关闭，新增科目不会随价格发布自动开通。
INSERT INTO pf_org_capability_policy(tenant_id,org_id,allowed_subjects)
SELECT d.tenant_id,o.dept_id,
 '["link_resolution","copy","asr","digital","ai_video_768p","ai_video_2k","ai_flat","ai_elevation","budget_estimate","ai_prompt","note_polish"]'::jsonb
FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id WHERE o.kind='ORG';
CREATE TABLE pf_ops_action_audit (
 id varchar(36) PRIMARY KEY, tenant_id bigint NOT NULL, org_id bigint NOT NULL,
 actor_id bigint NOT NULL, action varchar(40) NOT NULL, request_key varchar(100) NOT NULL,
 request_hash varchar(64) NOT NULL, reason varchar(500) NOT NULL,
 before_data jsonb NOT NULL DEFAULT '{}'::jsonb, after_data jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,request_key)
);
CREATE INDEX idx_pf_ops_audit_org ON pf_ops_action_audit(tenant_id,org_id,created_at DESC);
CREATE TABLE pf_task_reconciliation (
 tenant_id bigint NOT NULL, org_id bigint NOT NULL, task_key varchar(120) NOT NULL,
 status varchar(24) NOT NULL CHECK(status IN ('OPEN','WAITING_EXTERNAL','RESOLVED')),
 version bigint NOT NULL DEFAULT 1, decision varchar(40) NOT NULL, reason varchar(500) NOT NULL,
 evidence varchar(1000) NOT NULL, operator_id bigint NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,task_key)
);
CREATE INDEX idx_pf_task_reconciliation_org ON pf_task_reconciliation(tenant_id,org_id,updated_at DESC);
COMMENT ON TABLE pf_org_capability_policy IS '组织AI能力开通；无行或不在允许集合时禁止新调用，历史结算不受影响';
COMMENT ON TABLE pf_ops_action_audit IS '资料、能力与任务核对的幂等审计；不保存密码或供应商密钥';

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
SELECT 910073001,'任务与积分核对','platform:operations:query',2,30,0,'platform-task-ops','ep:finished','platform/operations/index','PlatformTaskOperations',0,true,true,false,'system','system'
WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='platform:operations:query');
INSERT INTO system_role_menu(id,role_id,menu_id,creator,updater,tenant_id)
SELECT (SELECT COALESCE(MAX(id),0) FROM system_role_menu)+row_number() OVER(ORDER BY r.id),r.id,m.id,'system','system',r.tenant_id
FROM system_role r CROSS JOIN system_menu m WHERE r.code='super_admin' AND r.status=0 AND m.permission='platform:operations:query'
AND NOT EXISTS(SELECT 1 FROM system_role_menu x WHERE x.role_id=r.id AND x.menu_id=m.id);
