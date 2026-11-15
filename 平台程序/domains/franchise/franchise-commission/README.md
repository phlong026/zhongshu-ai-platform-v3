# 设计委托六岗位契约

本模块本轮仅校准业务、门店老板、平台设计与工程移交；商城不在范围内。不上传或切换小程序版本，不改 A 系统。

## API

- `GET /franchise/customers/{customerId}/commission-context`：先校验 CRM 可读，返回 `customerId/commissionId/latestBudget/feeOffer/commission/allowedActions`。首次提交用 customerId；后续详情和命令均用真实 commissionId。
- `POST /franchise/customers/{customerId}/commission`：`AREA_FULL` 要求 `mode,budgetVersion,feeRuleVersion,text,scope,confirmed`；`LEGACY_SPLIT` 要求 `mode,budgetVersion,text,scope,totalCents,firstCents,recipient,confirmed`。可选 `attachmentAssetId` 仅接受本门店 ACCEPTED CONTRACT_ATTACHMENT。旧金额元字段仅保留兼容。过期预算或总部规则报 `1111000021`，不静默切换最新版本。
- `GET /franchise/design-pricing-policies/current`、`POST /franchise/design-pricing-policies`：仅总部设计行政，写入 `{rateCents,scope,recipient,active}`，追加不可变版本。与门店老板历史 `/design-policies` 分离。
- `GET /franchise/commissions`：分页 `CommissionSummary{commission,commissionId,displayStatus,allowedActions}`；完整详情另取 `/commissions/{id}`。
- `GET /franchise/commissions/{id}/handover-receivers`：当前可写业务的同店有效工程接收人 `{userId,name,roleCode}`，排除本人，不授予组织管理员权限。
- `POST /franchise/commissions/{id}/commands/design-dispatch`：`{recipient,note,drawingVersion,resultAssetId,drawingAssetId,reviewAssetId,confirmed}`；三份本总部 ACCEPTED DESIGN_DELIVERABLE 形成不可变 manifest。
- `packet-submit`：`{dispatchSeq,confirmationAssetId,confirmationDate,note,managerUserId,confirmed}`。成果完全继承 manifest；拒绝客户端传入替换文件。确认凭据为本门店 ACCEPTED CONTRACT_ATTACHMENT；指定工程接收人必须为不同 userId。
- 以上写入均须 `Idempotency-Key`。`commission-feedback` 仅接受说明，不接受未校验文件。

详情追加 `commissionId/settlement/dispatches`。资金投影包含 mode、当前 submissionVersion 和 FULL/FIRST/TAIL 各阶段的应付、门店净付、总部净收及 ready。总部设计行政保持原金额隔离：详情 `settlement=null`，提交金额、收款方和审批金额也裁剪；可办态通过 allowedActions/displayStatus 提供。财务岗位从专属 design-fees 接口取资金明细。提交快照增加 mode/rateCents/pricingVersion/attachmentAssetId。

## 事务与授权

AREA_FULL 按预算面积 × 总部单价，正数 HALF_UP 到分，首尾款均为零，唯一 FULL 阶段。完成和下发均要求两边净额足额。LEGACY_SPLIT 完成只需 FIRST 门店净付，完成事务创建 TAIL 应付；下发要求 FIRST、TAIL 两侧足额。退款不改历史完成/下发事实，后续动作重算，已下发但净额不足生成异常待办。

命令锁 → 锁内重放 → 资金阶段锁（FULL 或 FIRST→TAIL）→ 更新行。Settlement 的 ensure/lockAndRead 必须加入同一外层事务。旧 markReady 只保留审计兼容，不参与动作门禁。

`CommissionDeliverableAccessAdapter` 是 mapper-only 范围授权投影，不依赖资产服务或委托命令服务，避免资产票据回调循环。RESULT/DRAWING/REVIEW 来自 manifest；CONFIRMATION 来自当前移交包，仅门店参与人可读。撤回/替换下发后旧 scope 失效；通用资产通道不放宽。

## 迁移和回退

`.981` 新增总部单价策略和费用模式快照，存量明确为 LEGACY_SPLIT；`.982` 新增下发 manifest 与确认资产列。undo 发现新模式/manifest 业务事实会主动拒绝丢弃数据。已有新业务数据时恢复隔离 B 数据库和旧 JAR 备份，不能只执行 git revert。

## 验证边界

`SixRoleCommissionIntegrationTest` 使用真实 PostgreSQL 和真实 Finance Settlement adapter，覆盖版本拒绝、单期计价、双边不足/退款、成果回滚及授权。资产字节传输是明确测试替身；旧状态机测试的资金行是 TEST ONLY fixtures，不代表真实微信/银行支付。真实 DevTools 六岗操作和完整生产 wiring 由主集成通道另验。
