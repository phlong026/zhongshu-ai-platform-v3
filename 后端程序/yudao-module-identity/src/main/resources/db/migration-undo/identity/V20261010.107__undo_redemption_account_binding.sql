-- 回滚 V20261010.406：移除兑换账户绑定三列（列上无索引/约束，直接删安全）
ALTER TABLE access_code_redemption DROP COLUMN IF EXISTS company_name;
ALTER TABLE access_code_redemption DROP COLUMN IF EXISTS contact_name;
ALTER TABLE access_code_redemption DROP COLUMN IF EXISTS contact_phone;
