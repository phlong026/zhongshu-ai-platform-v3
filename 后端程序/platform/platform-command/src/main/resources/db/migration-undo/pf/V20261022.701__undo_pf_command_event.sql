-- 回滚 V20261022.701：删除平台命令事件日志表（会丢失全部命令事件，仅用于未上线环境或回滚演练）
DROP TABLE IF EXISTS pf_command_event;
