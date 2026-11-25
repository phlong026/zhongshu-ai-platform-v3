# R09 外部设计委托契约

本任务复用已有委托编号、每客户一条委托、CRM 对象权限及命令审计。新委托不依赖总部设计行政、内部预算、定价规则或内部出图。总部的全品牌查询继续走独立只读接口。

## 状态与版本

- 新建或变更委托需求：`EXTERNAL_PENDING`。填写实际委托日期、外部服务方、需求及服务范围，可附外部资料。
- 登记已收到的成果：`EXTERNAL_READY`。实际交付日期、图纸版本及成果、施工图、专业审核依据三个受控附件齐全才允许登记。
- 每次需求和成果登记分别追加不可变版本；更正必须给出原因。更新需求使上一版成果成为历史，须重新登记对应的新成果。
- R10 再从当前成果版本提交工程移交、退回或接收。接收中的移交和已接收委托不能回退到外部设计中。
- 旧 `FEE_PENDING/FEE_RETURNED/DESIGNING/DESIGN_COMPLETED/DISPATCHED/HANDOVER_RETURNED` 可由有权业务人员显式转外部流程，必须带当前 revision 和原因。旧版本、费用审批、财务、下发和移交记录全部保留。历史模拟委托未完成逐单真实结算审核时不能转换。
- 转换只将本组织、该委托仍待批的旧费用审批标记为既有 `SUPERSEDED` 状态，保留记录并冻结 `previousStatus/supersededApprovalIds`。已批准/退回审批和已有费用流水不改；转换与原费用审批采用同一 `commission → approval → settlement` 锁顺序。

## 接口

统一前缀 `/franchise/customers/{customerId}/external-entrust`，客户及资产编号保持十进制字符串。所有写命令使用必填 `Idempotency-Key`（去首尾空格，最多80字符）；同键必须保持同一命令和内容。冲突为 `1099000001`，键复用为 `1099000000`，输入400、状态409、对象不可见404。

| 方法/路径 | 用途 |
| --- | --- |
| GET 基础路径 | 当前委托、需求/成果版本、付款计划引用和当前可执行动作；不存在返回 revision=0，不自动建委托 |
| POST 基础路径 | 首次或后续需求登记；新需求使成果待重新登记 |
| POST `/convert-legacy` | 将符合条件的旧在途委托转为外部流程，保留旧编号 |
| POST `/results` | 登记外部成果版本 |
| POST `/{requests\|results}/{version}/assets/{assetId}/download-tickets` | 当前或历史资料受控下载，每次仍验证客户权限 |

需求登记/转换请求：

```json
{
  "revision": 0,
  "entrustedOn": "2026-11-25",
  "providerName": "外部设计服务方",
  "providerReference": "外部订单或项目编号（可空）",
  "text": "需求说明，最多2000字",
  "scope": "委托范围，最多500字",
  "area": 260.5,
  "floors": 2,
  "attachments": [{"assetId": "123", "title": "测量资料"}],
  "paymentPlanRef": {"source":"NONE", "revision":0, "contractId":null, "contractPlanVersion":null},
  "reason": "首次登记可空；变更或旧流程转换必填，最多500字"
}
```

`providerName` 最多100字、外部编号100字、附件名称180字；面积及层数可空，不从内部预算补造。附件最多20个，全部为已验收的 `BUSINESS_DOCUMENT`，不接受URL或其他公司的资产。

`paymentPlanRef` 必须原样带回 GET 返回的当前引用，来源 `NONE/DRAFT/CONTRACT`。服务端在提交事务中通过 `CustomerPaymentPlanDraftPort.snapshotForEntrust` 读取并冻结完整付款计划，检查引用仍一致。正式已签合同优先；草案只留快照，不生成应收、不生成支付单。返回 `currentPaymentPlan` 和每版需求的 `paymentPlan` 可展示来源和各期约定。

成果登记请求：

```json
{
  "revision": 1,
  "requestVersion": 1,
  "deliveredOn": "2026-11-25",
  "drawingVersion": "外部图纸V1",
  "resultAssetId": "124",
  "drawingAssetId": "125",
  "reviewAssetId": "126",
  "note": "本次成果说明；更正已有成果必须说明原因，最多1000字"
}
```

日期不能晚于上海时区当天。登记成果不会更改八步的完成率，也不会伪造合同或工程开工。

响应包含 `commissionId/customerId/customerName/mode/status/revision/canWrite/canConvert/allowedActions/currentRequest/currentResult/requests/results/currentPaymentPlan/currentPaymentPlanRef`。需求和成果版本含版本号、实际日期、经办人编号、创建时间及附件。旧委托 `mode=LEGACY`，尚无外部版本；前端显示转换入口及现有历史记录入口。

## 权限与兼容

业务员仅办理自己客户，业务负责人按团队范围办理，老板只读；公司、tenant、操作者来自当前身份，不接受请求指定。跨品牌、跨加盟商、同公司不可见客户均拒绝。资料当前与历史引用共同归属客户，转交后旧经办人持有的下载票据也不能读取。相同资产不能绑定到另一客户以扩大可见范围。

新记录 `workflow_mode=EXTERNAL`；旧记录保持 `LEGACY`。新委托的 `design_org_id` 使用本公司作为本地登记归属，不代表总部参与，不产生设计费往来。转换旧记录保留原设计组织及旧财务来源。旧兼容接口继续可读，不能通过旧设计完成/下发或费用命令改写外部流程。

## 验证

新增 `ExternalCommissionPgIntegrationTest` 的13项真实 PostgreSQL 回归覆盖无内部预算/无总部依赖建委托、付款快照冻结和版本冲突、并发重试、隔离/转交与I/O后复查、成果验收、旧状态转换、两种审批/转换并发顺序以及审计/事务回滚。复用实际CRM、上传扫描、下载票据、付款草案、旧委托/审批服务；仅对象存储与旧费用核算端口使用测试替身，旧费用真实核算由原财务回归继续验证。实际执行结果以配对CI为准；本机无Maven及PostgreSQL，不将Java语法检查写成测试通过。
