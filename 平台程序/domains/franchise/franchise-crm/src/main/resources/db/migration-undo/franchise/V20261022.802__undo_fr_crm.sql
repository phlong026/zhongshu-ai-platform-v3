-- 回滚 V20261022.802：须先回滚依赖客户的委托表（V20261022.803）
DROP TABLE IF EXISTS fr_customer_scheme;
DROP TABLE IF EXISTS fr_customer_follow;
DROP TABLE IF EXISTS fr_customer;
DROP TABLE IF EXISTS fr_lead;
