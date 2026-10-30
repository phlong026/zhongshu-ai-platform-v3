-- 回滚 V20261022.703：删除平台审批表（会丢失全部审批记录，仅用于未上线环境或回滚演练）
DROP TABLE IF EXISTS pf_approval;
