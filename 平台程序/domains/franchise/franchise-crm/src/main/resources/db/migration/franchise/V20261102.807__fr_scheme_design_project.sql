-- V20261102.807：fr_customer_scheme 增加 AI 设计项目桥接列（B2 板块 A / A7）
-- 归属：franchise-crm（fr_customer_scheme 所在模块）；franchise-design 经 CustomerSchemePort 写入。
-- 回写契约：PLAN/FACADE/BUDGET 均可携带 designProjectId；只追加语义不变；设计项目与客户的关联仅存本列（design 侧不感知 fr_* 表）。

ALTER TABLE fr_customer_scheme ADD COLUMN design_project_id BIGINT NULL;

CREATE INDEX idx_fr_customer_scheme_design_project
    ON fr_customer_scheme (design_project_id)
    WHERE design_project_id IS NOT NULL;
