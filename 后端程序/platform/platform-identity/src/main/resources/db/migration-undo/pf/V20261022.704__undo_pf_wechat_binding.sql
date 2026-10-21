-- 回滚 V20261022.704：删除员工微信绑定及其审计表（员工需重新绑定，审计记录丢失；仅用于未上线环境或回滚演练）
DROP TABLE IF EXISTS pf_wechat_binding_event;
DROP TABLE IF EXISTS pf_wechat_binding;
