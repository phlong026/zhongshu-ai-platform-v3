-- 回滚 V20261022.702：删除平台组织与任职扩展表（system_dept 中已创建的部门不受影响，需另行清理）
DROP TABLE IF EXISTS pf_staff_post;
DROP TABLE IF EXISTS pf_org_unit;
DROP TABLE IF EXISTS pf_post_template;
DROP TABLE IF EXISTS pf_org_type;
