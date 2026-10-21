-- 回滚 V20261010.108：移除绑定变更审计表（独立表，无外部引用，直接删安全）
DROP TABLE IF EXISTS access_code_binding_audit;
