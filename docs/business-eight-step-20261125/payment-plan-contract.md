# R11 自定义付款计划

## 创建与读取

`POST /franchise/sales/contracts` 增加 `paymentStages`。填写时原三期金额可省略；未填写时保留旧三期协议。新建节点不传 stageId。

```json
{"customerId":"1","totalCents":100000,"paymentStages":[
  {"label":"签约款","ratioBps":3000,"trigger":"BEFORE_START"},
  {"label":"基础验收款","ratioBps":4000,"trigger":"NODE_COMPLETED","nodeIndex":2},
  {"label":"竣工款","ratioBps":3000,"trigger":"ON_FINISH"}
]}
```

这是结构示例，不是默认付款比例。允许 1–20 个节点，同一计划统一按金额 `amountCents` 或比例 `ratioBps`（10000=100%）填写。金额使用整数分；比例总计必须100%，前面各期向下取整，最后一期吸收分币尾差。每个新节点的实际金额必须大于0。

节点字段：`stageId,label,amountCents,ratioBps,dueDate,trigger,nodeIndex`。触发条件：ON_SIGN、BEFORE_START、NODE_COMPLETED（指定工程节点序号）、ON_FINISH、DATE（必须有日期）、MANUAL。旧期次保留 LEGACY_BASE_DONE/LEGACY_TOPPING 语义，不猜测项目节点序号。

`GET /franchise/sales/contracts/{id}/payment-plan` 返回 `contract,stages,canEdit,customerName`；包含合同 revision/paymentPlanVersion/paymentPlanMode。财务在合同领域读取本公司信息，不因此获得普通 CRM 客户权限。阶段响应沿用 stageId/stage/amountCents/collectedCents/refundedCents/balanceCents，并增加 label/sequence/ratioBps/trigger/nodeIndex/planVersion。

## 修改与版本

`POST /franchise/sales/contracts/{id}/payment-plan`：

```json
{"totalCents":100000,"revision":2,"reason":"双方确认调整付款约定","stages":[
  {"stageId":"原节点编号","label":"签约款","amountCents":30000,"trigger":"BEFORE_START"},
  {"label":"新增节点","amountCents":70000,"trigger":"MANUAL"}
]}
```

请求使用 Idempotency-Key。重复原请求不新增版本，旧 revision 拒绝。WAIT_SIGN 允许公司业务、负责人、财务办理；SIGNED 仅公司负责人/财务可调整；VOIDED 不可修改。删除任何有到账登记或收款引用的节点会拒绝；保留的节点金额不得低于已确认净收款。节点 ID/key 永久稳定，顺序、名称和金额变化不迁移收款/退款。自定义计划拒绝旧三期 change-amounts 命令，防止旧客户端覆盖。

`GET /franchise/sales/contracts/{id}/payment-plan/versions` 返回版本、合同 revision、金额、节点快照、原因、操作者及时间。历史金额查询使用现有 before-change 表，新增节点同时保存“此前不存在”记录。

## 跨领域契约

SaleContractQueryPort.findPaymentPlan/findPaymentPlanById 只返回本公司已签合同；草稿不能生成正式应收。PaymentPlan 包含 contractId/revision/version/totalCents/assetId/stages，每个 PaymentStage 包含 id/key/label/sequence/amountCents/ratioBps/dueDate/trigger/nodeIndex。UnifiedStageAccountPort.StageBalance 增加 label/trigger/nodeIndex/planVersion，保留原构造器兼容。

SQL 原表新增 `label,sequence,ratio_bps,trigger_type,trigger_node_index,plan_version`，合同新增 `payment_plan_version,payment_plan_mode`。R12 工程继承读取这些节点，不重新按三期填写。总部只读接口也按真实列表返回，不能硬编码三期。

## 数据迁移与验证

V20261125.1050 原地扩展，保留既有 stage ID、receipt/refund 引用和旧整期凭证。undo 在存在任何新计划或新版本时明确拒绝删除数据。

测试包含2/3/5期、比例尾差、同键重试、版本冲突、旧三期零额兼容、收款关联不移动、禁止删除有引用节点、净收款下限及跨公司权限。CI 加入 PaymentPlanRulesTest、FlexiblePaymentPlanIntegrationTest 和原 SaleContractThreeStageIntegrationTest。本地没有 Maven/PostgreSQL，Java语法检查不等同依赖编译或PG测试通过。
