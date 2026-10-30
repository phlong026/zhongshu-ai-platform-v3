-- 兑换账户绑定：激活码兑换时登记加盟商公司信息（2026-10-10 运营需求）
--
-- 业务：用户在小程序兑换激活码时，弹「账户绑定」弹窗登记加盟商公司名称、
--       联系人与电话，随兑换事实落库；管理端授权码列表据此展示"哪个码被哪家公司用了"。
-- 兼容：三列可空——历史兑换行与静默恢复路径不携带该信息，不回填。
ALTER TABLE access_code_redemption ADD COLUMN IF NOT EXISTS company_name VARCHAR(120);
ALTER TABLE access_code_redemption ADD COLUMN IF NOT EXISTS contact_name VARCHAR(60);
ALTER TABLE access_code_redemption ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(30);
