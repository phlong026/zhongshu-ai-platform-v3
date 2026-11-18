DROP TRIGGER IF EXISTS fr_eng_link_signed_contract ON fr_sale_contract;
DROP FUNCTION IF EXISTS fr_eng_link_new_signed_contract();
-- 已核对的合同归属属于业务档案，撤回代码时保留。
