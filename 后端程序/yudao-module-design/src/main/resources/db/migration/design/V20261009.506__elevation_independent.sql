-- V20261009.506：需求① 平面/立面拆分——纯立面项目允许无平面选择
-- design_result_version.flat_selection_id 由 NOT NULL 改为可空（NULL 表示纯立面项目的最终版本）
ALTER TABLE design_result_version
    ALTER COLUMN flat_selection_id DROP NOT NULL;
