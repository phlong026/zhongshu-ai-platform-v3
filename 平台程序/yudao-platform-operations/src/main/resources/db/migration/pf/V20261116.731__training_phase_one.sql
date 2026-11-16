CREATE TABLE pf_training_material (
 id varchar(40) PRIMARY KEY, tenant_id bigint NOT NULL, name varchar(200) NOT NULL,
 category varchar(60) NOT NULL, created_by bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE pf_training_material_version (
 id varchar(40) PRIMARY KEY, tenant_id bigint NOT NULL, material_id varchar(40) NOT NULL REFERENCES pf_training_material(id),
 version_no integer NOT NULL, file_name varchar(240) NOT NULL, extension varchar(12) NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes>0), sha256 varchar(64) NOT NULL,
 state varchar(24) NOT NULL DEFAULT 'UPLOADING', error varchar(500) NOT NULL DEFAULT '',
 original_key varchar(180), preview_key varchar(180), media_key varchar(180), poster_key varchar(180),
 duration numeric NOT NULL DEFAULT 0, lease_until timestamptz, created_by bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(material_id,version_no)
);
CREATE TABLE pf_training_upload (
 id varchar(40) PRIMARY KEY, tenant_id bigint NOT NULL, actor_id bigint NOT NULL,
 version_id varchar(40) NOT NULL REFERENCES pf_training_material_version(id), request_key varchar(100) NOT NULL,
 fingerprint varchar(64) NOT NULL, chunk_size integer NOT NULL, chunk_count integer NOT NULL,
 state varchar(24) NOT NULL DEFAULT 'UPLOADING', created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,actor_id,request_key)
);
CREATE TABLE pf_training_upload_part (
 upload_id varchar(40) NOT NULL REFERENCES pf_training_upload(id), part_no integer NOT NULL,
 size_bytes integer NOT NULL, sha256 varchar(64) NOT NULL, PRIMARY KEY(upload_id,part_no)
);
CREATE TABLE pf_training_course (
 id varchar(40) PRIMARY KEY, tenant_id bigint NOT NULL, revision bigint NOT NULL DEFAULT 0,
 draft jsonb NOT NULL, review_state varchar(24) NOT NULL DEFAULT 'DRAFT',
 active_release varchar(40), state varchar(24) NOT NULL DEFAULT 'DRAFT',
 created_by bigint NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE pf_training_release (
 id varchar(40) PRIMARY KEY, tenant_id bigint NOT NULL, course_id varchar(40) NOT NULL REFERENCES pf_training_course(id),
 release_no integer NOT NULL, content jsonb NOT NULL, published_by bigint NOT NULL,
 published_at timestamptz NOT NULL DEFAULT now(), UNIQUE(course_id,release_no)
);
CREATE TABLE pf_training_audit (
 id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, actor_id bigint NOT NULL,
 object_id varchar(40) NOT NULL, action varchar(40) NOT NULL, revision bigint,
 detail varchar(500) NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE pf_training_ticket (
 token_hash varchar(64) PRIMARY KEY, tenant_id bigint NOT NULL, actor_id bigint NOT NULL,
 post_id bigint, course_id varchar(40), release_id varchar(40), version_id varchar(40) NOT NULL,
 purpose varchar(20) NOT NULL, expires_at timestamptz NOT NULL
);
CREATE TABLE pf_training_progress (
 tenant_id bigint NOT NULL, org_id bigint NOT NULL, user_id bigint NOT NULL,
 course_id varchar(40) NOT NULL, lesson_id varchar(40) NOT NULL, version_id varchar(40) NOT NULL,
 ranges jsonb NOT NULL DEFAULT '[]', completed boolean NOT NULL DEFAULT false,
 position numeric NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,org_id,user_id,course_id,lesson_id,version_id)
);
CREATE TABLE pf_training_play (
 id varchar(40) PRIMARY KEY, tenant_id bigint NOT NULL, org_id bigint NOT NULL, user_id bigint NOT NULL,
 course_id varchar(40) NOT NULL, lesson_id varchar(40) NOT NULL, version_id varchar(40) NOT NULL,
 release_id varchar(40) NOT NULL, position numeric NOT NULL DEFAULT 0,
 last_ping timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL DEFAULT now()+interval '4 hours'
);
CREATE INDEX pf_training_versions_material ON pf_training_material_version(tenant_id,material_id,version_no DESC);
CREATE INDEX pf_training_courses_state ON pf_training_course(tenant_id,state,updated_at DESC);
CREATE INDEX pf_training_tickets_expiry ON pf_training_ticket(expires_at);
CREATE INDEX pf_training_audit_object ON pf_training_audit(tenant_id,object_id,created_at DESC);

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
VALUES(873100,'学习培训','',1,35,0,'/training','ep:reading','','',0,true,true,true,'1','1'),
 (873101,'课程管理','training:admin',2,1,873100,'courses','ep:video-play','platform/training/index','TrainingCourses',0,true,false,false,'1','1'),
 (873102,'资料库','training:admin',2,2,873100,'materials','ep:folder-opened','platform/training/materials','TrainingMaterials',0,true,false,false,'1','1')
ON CONFLICT(id) DO NOTHING;
INSERT INTO system_role_menu(id,role_id,menu_id,creator,updater,tenant_id)
SELECT (SELECT COALESCE(MAX(id),0) FROM system_role_menu)+row_number() OVER(ORDER BY r.id,m.id),r.id,m.id,'1','1',r.tenant_id FROM system_role r CROSS JOIN system_menu m
WHERE r.code='super_admin' AND r.status=0 AND m.id IN(873100,873101,873102)
AND NOT EXISTS(SELECT 1 FROM system_role_menu x WHERE x.role_id=r.id AND x.menu_id=m.id AND x.tenant_id=r.tenant_id);
