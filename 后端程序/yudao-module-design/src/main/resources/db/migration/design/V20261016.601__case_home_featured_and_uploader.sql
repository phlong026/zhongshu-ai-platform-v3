-- 灵感库改版批次B（2026-10-16 需求确认 3b/3c）：
-- 1) design_case 增加运营「显示到首页」标记与标记时间；首页精选按标记时间倒序优先，
--    未标记的按「最新发布」补齐：case_publication 中该案例最近一次上架动作
--    （action='PUBLISH'）的 create_time 倒序（发布时间真值不在 design_case 上），同时间按案例 id 升序
-- 2) 记录上传人展示信息（公司·人名）：后台公司案例取操作人昵称+部门，AI 投稿案例取兑换绑定公司+联系人；
--    存量为 NULL，前端回退默认来源标签（公司案例/AI案例）

ALTER TABLE design_case
    ADD COLUMN home_featured BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN home_featured_at TIMESTAMP NULL,
    ADD COLUMN uploader_name VARCHAR(64) NULL DEFAULT '',
    ADD COLUMN uploader_company VARCHAR(64) NULL DEFAULT '';

CREATE INDEX idx_design_case_home_featured ON design_case (home_featured, home_featured_at);
