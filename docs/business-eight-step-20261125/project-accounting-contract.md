# R14 项目账核算与确认版本

## 复用范围

继续使用 `fr_fin_receipt` / `fr_fin_payment` / `fr_fin_payable` / `fr_fin_refund` 原单。项目直接归属、已关联前期账本、项目来源委托的设计费用和相应员工报销通过原账本 CTE 合并，OR 条件不会重复计入同一原单。确认核算不发起支付、不生成新的收付款、不改变工程结算状态。

`FinWorkbenchService.ledger` 的项目范围增加 `accounting`。总部 R02 现有公司账本入口因此自动获得核算结果，`canConfirm` 固定为 false；未增加总部写入权限。旧分页项目账取消固定期次过滤，所有真实动态付款节点均进入应收。

## 金额口径

| 字段 | 口径 |
| --- | --- |
| `contractRevenueCents` | 同公司项目关联的有效已签合同总额；缺少有效已签合同为 null |
| `confirmedCostCents` | 未删除且未作废的项目原始应付总额；员工报销应付不重复计成本 |
| `netReceivedCents` | 已登记收款减有效客户退款 |
| `companyNetPaidCents` | 公司实际付款减公司收到的支出退回；员工个人垫付不作公司现金 |
| `cashNetCents` | 净收款减公司净付款，展示为现金结余 |
| `grossProfitCents` | 合同总额减已确认成本，展示为按已确认项目费用计算的毛利；合同缺失为 null |
| `receivableCents` | 各个有效动态合同节点的约定金额减该节点净实收，逐节点取非负余额后求和 |

现金结余不表示利润。支出退回只改变现金；成本减少必须调整原应付。未归属费用及公司公共费用分摊不在本项目范围内，不推测税费、薪酬规则或未知费用。

金额采用十进制“分”字符串；编号也是字符串。费用汇总和来源指纹覆盖全量原单，不受账本页面 500 条显示上限影响。

## 普通门店 API

前缀 `/franchise/finance/workbench/projects/{projectId}/accounting`，沿用 `franchise:fin:query` 并在服务端以新事务重新校验当前身份、登录用户、真实租户及有效公司/任职；读取前后、确认加锁后和提交前再次复核。

- `GET /`：当前核算投影、`sourceHash`、当前 `version`、`state`、`latestSnapshot`、`ready`、`canConfirm`。
- `GET /snapshots?pageNo=1&pageSize=20`：历史确认，返回 `list/total/pageNo/pageSize`，每页最大 100。
- `POST /confirm`：需要 `Idempotency-Key`，body 为 `{sourceHash, expectedVersion, costsComplete:true, note}`。

门店财务、财务负责人可以确认；老板与业主只读；总部角色不能通过门店端点写入。确认前必须有有效已签合同且工程已经结算，同时要求经办人明确确认已发生项目费用归集完整并填写说明。服务端始终重新读来源，不信任客户端金额。

状态为 `UNCONFIRMED`（未确认）、`CURRENT`（来源未改变）、`STALE`（原账或合同已有变化，需再次核对）。`ready=false` 代表尚不符合确认条件。新确认版本保存当前完整金额快照，旧版本不修改。更改原账后旧确认仍可查看，不会悄悄重算历史利润。

同项目确认先取得数据库事务锁，再以单条 SQL 的一致快照读取全部金额与来源。不同请求以 `expectedVersion` 防止覆盖；同一幂等键返回原确认，换内容复用同键拒绝。来源摘要和历史持久化均在业务事务内；后续正常补录会令旧确认标记为 `STALE`。

## 迁移与验证

迁移 `V20261125.1047` 新增独立确认表。数据库拒绝 UPDATE / DELETE 历史确认；回滚脚本发现已有确认时拒绝删除数据。

新增真实 PostgreSQL 集成测试覆盖前期费用、委托设计费用、垫付与报销不重复计费、退款现金与成本区别、自定义应收、逻辑删除/作废、超过 500 条的来源指纹、历史不可变、幂等、范围/角色/结算前提、并发版本和事务回滚。已加入 release-regression 必跑名单。本地仅具 Java 语法解析工具，完整 Maven / PostgreSQL 验收由固定提交 CI 完成，不将解析结果表述为运行通过。
