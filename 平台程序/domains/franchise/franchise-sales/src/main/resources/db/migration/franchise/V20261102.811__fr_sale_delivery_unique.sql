-- V20261102.811：交付唯一性（S1-22）——一份合同至多一条有效交付。
-- 原先仅靠前端创建前 R14 探测，直发请求可对同一合同重复创建交付；
-- 部分唯一索引兜底后，服务层捕获冲突转 DELIVERY_DUPLICATE。

CREATE UNIQUE INDEX uk_fr_sale_delivery_contract ON fr_sale_delivery (contract_id);
