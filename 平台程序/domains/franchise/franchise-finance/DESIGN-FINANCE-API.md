# 设计财务与项目账（R03）

前缀 `/franchise/finance`，沿用当前用户、`X-Post-Id`、tenant 及 finance 查询/登记/作废权限。所有写入金额为分，ID 为不透明长整数字符串。仅隔离 B 线下事实登记，不发起真实支付。

## 可办理来源

`GET /design-fees?pageNo=1&pageSize=10` 返回 `PageResult<Item>`（`list,total`）。Item 包含 commissionId、submissionVersion、customerId、companyId、designOrgId、mode、stage、payableId、expectedCents、storePaidNetCents、hqReceivedNetCents、storeReady、hqReady、financialException、payments、allowedActions。

payments 为 paymentId、amountCents、netPaidCents、receivedNetCents、receivableCents；allowedActions 为 `PAYMENT_REGISTER` 或 `RECEIPT_REGISTER`。只查询当前组织参与的不可变来源应付。历史版本可查账但无新办理动作，不借用平台设计的委托列表权限。originPaymentOverReceivedCents 单列退回后原付款净额小于有效核收的异常金额；financialException 同时覆盖此类来源差额及下发后阶段净额不足。总部净额照实展示，但 headquartersReady/fullyReady 还要求每笔有效核收仍关联本阶段同来源的有效付款、且累计不超过该原付款当前净额；新补付款不能复用挂在已退回原付款上的旧核收。

`AREA_FULL` 仅 FULL，first/tail 不拆比例；`LEGACY_SPLIT` 为 FIRST/TAIL，TAIL 在设计完成事务内生成。来源唯一键为 `(company_id,source_type,source_ref)`，sourceRef 为 `commissionId:submissionVersion:STAGE`。来源、客户、两侧组织、阶段及应付金额不可更新。

## 门店付款与平台分笔核收

- `POST /payments`：`{payableId,amountCents,payDate,transactionNo?,voucherFileId?}`。
- 付款非空流水复用既有 fr_fin_txn_no 永久组织身份，禁止不同幂等键或并发重复登记，作废不释放；历史已有流水但未占身份的付款也查重，无流水手工路径保持兼容。
- 共享历史付款 Guard 同时保护收据、两方向退款及跨端 bank-confirm，历史付款作废也不释放流水；银行确认成功同样写 receipt 永久身份，防后续付款/退款跨类型复用。
- `POST /receipts`：`{sourceType:"design",designCommissionId,designStage,originPaymentId,amountCents,receivedDate,method:"bank",transactionNo?,voucherFileId?,name?}`。平台组织、客户、应付来源及版本由服务器验证，不能客户端指定另一个归属。允许真实分笔，不覆盖为整期金额；有效累计核收不得超过原付款减有效支出退回的净额。
- `POST /receipts/{id}/revision` 沿用 baseRevisionVersion、newAmountCents、note；设计核收增加金额仍受原付款净额限制。
- `DELETE /receipts/{id}`：设计核收作废，`{reason}`；有效退款须先更正，保留原流水身份。付款作废属于更正，仍有有效关联总部净核收时拒绝；真实支出退回仍允许登记并投影资金来源异常。
- `POST /refunds`、`POST /expense-refunds` 沿用现有 DTO；退款/支出退回实时冲减，作废恢复时仍复核余额。设计付款/退款作废和核收修订、作废均须 `Idempotency-Key`，非设计手工路径保持原兼容行为。

所有设计写入锁序为：命令事务 advisory 锁 → 锁内幂等重放 → `design-fee:id:version:STAGE` 锁 → 行锁。多期固定 FIRST→TAIL。衍生应付和 lockAndRead 以 MANDATORY 加入委托外层事务；无事务拒绝，外层失败共同回滚。查询和待办复用实时净额，不调用 dev markReady。

## 财务项目列表与总账

- `GET /projects?pageNo=1&pageSize=10&keyword=`：本组织 PageResult，条目 `{projectId,name,status,sourceCommissionId}`。keyword 搜索名称或精确项目编号，汇总总数与分页同范围。
- `GET /projects/{projectId}/ledger?pageNo=1&pageSize=10`：`{summary,entries:{list,total}}`。
- summary：receivableCents、receivedCents、refundedCents、paidCents、expenseRefundedCents、payableCents，均为各自毛额，现金净收=`received-refunded`，现金净付=`paid-expenseRefunded`。合同应收从工程冻结的本组织 SIGNED、未删除合同的三个有效正金额阶段合计，已收款不减少合同应收毛额，作废/未签合同不计。
- entries：`{id,type,occurredOn,amountCents,sourceRef,status}`；type 为 PAYABLE/PAYMENT/RECEIVABLE/RECEIPT/REFUND/EXPENSE_REFUND/PENDING_RECEIPT。RECEIVABLE 使用合同阶段 id 和签约日期；历史待登记收据仅在未被该合同阶段关联覆盖时单列 PENDING_RECEIPT，不计合同应收/现金实收。业务日期缺失保留 null，不拿创建时间代填现金发生日期；应付日期为创建日期。
- 汇总与明细共用 scoped CTE，按 occurred_on DESC NULLS LAST、id DESC、type DESC 稳定排序，沿工程 source_commission_id 纳入前置设计支出/退回；统一建房三期沿工程冻结 sale_contract_id→fr_sale_stage_receipt 及 fr_eng_collection.receipt_id 纳入，不依赖收据 projectId、不按客户模糊归属，多条关系命中同一实收仍只计一次。跨组织拒绝。

## 迁移及回退

仅新增 V20261115.983：应付来源/两侧组织/客户、核收 originPaymentId、索引及设计来源不可变触发器。历史手工账不伪迁移。undo 有数据前置检查：有新设计账关系时禁止删列，应恢复部署前隔离 B 数据库备份；代码 git revert 不等于财务数据回退。

原生小程序/部署/双微信人员验收由集成运行记录单独证明；模块 PG 测试不替代这些验收。

## 工程材料未付款条款修订

工程 `POST /franchise/engineering/supplies/{supplyId}/terms`（沿用现有控制器路径）仅在 supply.payableId 为空时创建应付；已有应付通过 EngineeringPayablePort.updateUnpaid 加入工程外层事务，修订同一应付的 total/recipient/agreementRef，不用新随机键创建替代账。

锁序为命令锁→供应事项行锁→应付行锁，与财务登记付款共用同一应付行锁。锁内复核应付组织、项目、engineering 阶段、供应事项的精确来源及本组织实时净有效付款为 0；已付款拒绝改价。修订和工程条款共同提交/回滚，财务事件记录真实 actor 及递增应付事件版本。未融合实现的默认更新端口拒绝执行。

terms 幂等指纹覆盖 supplyId、规范化 supplier/unitPrice/freight、basis、contact、ISO promiseDate；同键同完整命令重放，同键更换凭据、联系人或约定日期拒绝，不返回假成功。前端调用沿用原工程接口，无新增公开财务写入口。
