CREATE TABLE pf_training_material_category (
 id varchar(36) PRIMARY KEY, tenant_id bigint NOT NULL, name varchar(40) NOT NULL,
 sort_order integer NOT NULL DEFAULT 100, system_default boolean NOT NULL DEFAULT false,
 revision bigint NOT NULL DEFAULT 0, created_by bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE UNIQUE INDEX pf_training_material_category_name ON pf_training_material_category(tenant_id,lower(name)) WHERE deleted_at IS NULL;
WITH tenants AS (SELECT 1::bigint AS tenant_id UNION SELECT tenant_id FROM pf_training_material),
names(name,sort_order,system_default) AS (VALUES ('AI短视频获客',0,false),('业务销售',1,false),('工程施工',2,false),('财务对账',3,false),('设计交付',4,false),('经营管理',5,false),('未分类',9999,true))
INSERT INTO pf_training_material_category(id,tenant_id,name,sort_order,system_default)
SELECT md5('training-category:'||tenant_id::text||':'||name)::uuid::text,tenant_id,name,sort_order,system_default FROM tenants CROSS JOIN names;
-- Preserve any already-uploaded custom category names.
INSERT INTO pf_training_material_category(id,tenant_id,name)
SELECT md5('training-category:'||tenant_id::text||':'||category)::uuid::text,tenant_id,category FROM pf_training_material
WHERE category NOT IN ('AI短视频获客','业务销售','工程施工','财务对账','设计交付','经营管理','未分类') GROUP BY tenant_id,category;
