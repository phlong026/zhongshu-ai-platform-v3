-- 回滚 V20261016.109（部分唯一索引 + 已删账号身份自愈）。
-- 注意：恢复全量唯一约束前必须物理清除软删身份行——若同一 (appid, openid) 存在一软删一在用
-- 的成对行，全量唯一约束会创建失败；这些映射行的删除事实已在 audit_event 留痕。
-- 回滚后「已删除账号的微信」将重新出现僵尸映射（回到 109 之前的缺陷行为）。
DELETE FROM wechat_identity WHERE deleted = TRUE;
DROP INDEX IF EXISTS uk_wechat_identity_appid_openid;
ALTER TABLE wechat_identity ADD CONSTRAINT uk_wechat_identity_appid_openid UNIQUE (appid, openid);
