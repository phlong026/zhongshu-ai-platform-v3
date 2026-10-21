-- 修复：删除账号后同一微信永久无法重新注册（兑换激活码报「账号已停用或关闭」）。
--
-- 根因：运营删除账号只软删 account 行，wechat_identity 的 (appid, openid) 映射残留；
--       该微信再次登录仍解析到僵尸账号（登录不发账号状态），兑换时按 deleted=FALSE 查账号行
--       查不到 → 403「账号已停用或关闭」，且永远无法重新注册。
-- 修复：① (appid, openid) 唯一约束改为部分唯一索引（仅约束未删除行）——身份软删后同一微信可重建；
--       ② 历史数据自愈：已删除账号的身份映射一并软删，其微信下次登录按新用户重新建号。
-- Reversibility: messy——索引可回滚（见 undo），自愈 UPDATE 不可逆（软删身份无法与历史脏数据区分）。
ALTER TABLE wechat_identity DROP CONSTRAINT IF EXISTS uk_wechat_identity_appid_openid;
CREATE UNIQUE INDEX uk_wechat_identity_appid_openid ON wechat_identity (appid, openid) WHERE deleted = FALSE;

UPDATE wechat_identity w SET deleted = TRUE, update_time = now()
FROM account a WHERE a.id = w.account_id AND a.deleted = TRUE AND w.deleted = FALSE;
