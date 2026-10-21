-- V20261005.503：方案重命名（2026-10-05 整改）——项目级自定义名称，仅属主可改。
-- 空值表示未命名：列表/详情沿用「项目编号 + 时间」展示，不回填默认名。
ALTER TABLE design_project ADD COLUMN name VARCHAR(60) NULL;
