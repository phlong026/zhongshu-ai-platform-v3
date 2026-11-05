-- V20261104.829 undo：移除异常资金登记载体及其数据
-- ⚠️ 本表承载真实资金事实留痕，回滚前如需保留必须先线下导出。
DROP TABLE IF EXISTS fr_fin_abnormal_fund;
DELETE FROM flyway_schema_history WHERE version = '20261104.829' AND description LIKE '%fr_fin_abnormal_fund%';
