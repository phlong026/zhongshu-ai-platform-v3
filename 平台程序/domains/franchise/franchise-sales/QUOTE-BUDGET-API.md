# 报价与委托预算版本

`POST /admin-api/franchise/sales/quotes` 保持既有请求，要求有效开间/进深（最多两位小数）、1至20层；建筑面积优先冻结前端人工核定的实际area（大于0、最多两位小数）；兼容缺失area的旧请求时才按开间×进深×层数计算，不能覆盖中空/退台等实际面积。行金额仍由服务端计算。Idempotency-Key必填且不超过80字符，指纹包含规范化尺寸、面积、地址和priceVersion。同键同内容返回原quoteId，同键异内容拒绝。

在同一事务内先锁命令键并重放检查，再取得与委托提交共用的客户锁，写入一个BUDGET客户方案版本，冻结quoteId/priceVersion/尺寸/金额，并让报价关联budget_scheme_id/budget_version。返回原有quoteId/totalCents/items，新增budgetSchemeId/budgetVersion。原报价的可选来源schemeId继续保留；新预算关联使用独立列。

旧报价保持原样。正常重新保存预算后才能获得新委托依据，不在GET列表或详情中补写。回退表结构保留已产生的客户预算事实；业务数据回退需恢复B库备份。

SaleQuoteBudgetIntegrationTest使用真实PG与CRM适配器，覆盖原子回写、同键重放、尺寸/版本冲突、同键并发及外层事务回滚。该证据与小程序原生页面验收分别记录。
