-- 回滚 V20261022.802：删除客户、客资、跟进（数据丢失，仅用于未上线环境或回滚演练；须先回滚依赖客户的后续迁移）
DROP TABLE IF EXISTS fr_follow_up;
DROP TABLE IF EXISTS fr_lead;
DROP TABLE IF EXISTS fr_customer;
