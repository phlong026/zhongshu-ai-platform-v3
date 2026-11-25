-- The /platform-announcements page is statically registered by the B admin app.
-- Permission-only menus avoid registering a second dynamic page at the same path.
INSERT INTO system_role(id,name,code,sort,data_scope,data_scope_dept_ids,status,type,remark,creator,updater,tenant_id)
SELECT (SELECT COALESCE(MAX(id),0) FROM system_role)+row_number() OVER(ORDER BY tenant_id),
       '总部管理员','hq-admin',114,1,'',0,1,'总部管理岗位；本次仅授予公告权限','1','1',tenant_id
FROM (SELECT DISTINCT tenant_id FROM system_role r WHERE r.code IN ('super_admin','boss','finance') AND r.status=0
      AND NOT EXISTS(SELECT 1 FROM system_role a WHERE a.tenant_id=r.tenant_id AND a.code='hq-admin')) tenants;

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
VALUES (874101,'查看公司公告','platform:announcement:query',3,1,0,'','','','',0,true,false,false,'1','1'),
       (874102,'管理公司公告','platform:announcement:manage',3,2,0,'','','','',0,true,false,false,'1','1');

INSERT INTO system_role_menu(id,role_id,menu_id,creator,updater,tenant_id)
SELECT (SELECT COALESCE(MAX(id),0) FROM system_role_menu)+row_number() OVER(ORDER BY r.id,m.id),
       r.id,m.id,'1','1',r.tenant_id
FROM system_role r JOIN system_menu m ON
    (m.id=874101 AND r.code IN ('super_admin','hq-admin','finance','finance-lead'))
    OR (m.id=874102 AND r.code IN ('super_admin','hq-admin'))
WHERE r.status=0
  AND NOT EXISTS(SELECT 1 FROM system_role_menu g WHERE g.role_id=r.id AND g.menu_id=m.id AND g.tenant_id=r.tenant_id);
-- finance is shared by stores and headquarters. Server authorization additionally requires
-- a currently selected, active headquarters post; store finance gains no cross-brand access.
