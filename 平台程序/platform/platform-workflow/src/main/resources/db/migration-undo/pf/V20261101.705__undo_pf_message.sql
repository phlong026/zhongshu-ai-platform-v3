-- 回滚 V20261101.705：删除平台员工消息表（会丢失全部消息记录与已读状态，仅用于未上线环境或回滚演练）
DROP TABLE IF EXISTS pf_message;
