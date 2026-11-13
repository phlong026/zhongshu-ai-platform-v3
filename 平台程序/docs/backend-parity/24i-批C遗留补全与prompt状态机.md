# 24i · 批 C 遗留补全（岗位权限码 + AI_PROMPT 目录 + prompt 独立状态机）

> 输入：24 号评审遗留 P2（老板视图缺 `@post.has`）；D6 v2 §2.2/§2.3-B（`AI_PROMPT` 未注册目录、prompt 需独立状态机）。
> 状态：**已实施，待 Codex 评审**。

## 一、交付

| # | 交付 | 文件 |
|---|---|---|
| 1 | 岗位权限种子 `franchise:org:point:query/write`（菜单 710900 段，role_menu 714600 段；boss/owner 双授） | `franchise-system/V20261113.859`（+undo） |
| 2 | `/platform/org-point/*` 全部端点加 `@post.has` 门禁（读=query、额度/权限写=write），保留 `requireBoss` 纵深防御 | `OrgPointViewController` |
| 3 | `BillingPriceCatalog` 注册 `ai_prompt`（1 积分/次，v1） | `BillingPriceCatalog` |
| 4 | 端口补 `PoolBinding findBinding(...)` / `Long findOperationByBizKey(...)` | `OrgPointPort` + `OrgPointService` |
| 5 | **prompt 独立状态机（公司池 job）**：reserve→`ORG_RESERVED`；SUCCEEDED→settle(1)；NOT_SENT→release；DISPATCHED/UNKNOWN→保持 PENDING；幂等键 `design:prompt:{jobId}:{attempt}:{token}`；收据校验（callId=plan-{jobId}，SUCCEEDED 必带 sha256） | `AiJobSettlementService.reservePromptCall/finishPromptCall`（含带收据重载） |

## 二、语义要点

- **路由判据**：job 存在 `design_billing_binding`（ai_flat/ai_elevation）即公司池；否则 `prompt_pricing_version=0` 返回 `EXEMPT`（历史/App 行为不变）。
- **独立三态**：prompt 与图片 job 终态解耦——图片 FAILED 不释放 prompt 预留（任务书红线）；UNKNOWN 保持 PENDING 交人工核对。
- **幂等**：同一 `(jobId, attemptNo, token)` 重复预留命中既有 operation，不重复扣点；重放 finish 由池侧终态互斥保护。
- **鉴权双层**：`@post.has` 控制"当前岗位是否有此功能"，`requireBoss` 控制"是否本组织老板"；平台调点/迁移仍走 yudao 权限码。

## 三、验收

- `AiJobOrgChargeRoutingTest` **8/8**（新增：prompt 成功扣 1 次、NOT_SENT 释放、UNKNOWN 保持 PENDING、非公司池 job EXEMPT）。
- ai-orchestration 全套 / commerce orgpoint 全套 / 全仓 compile 结果见提交前复跑记录。

## 四、未做/边界

| 项 | 说明 |
|---|---|
| design 长期 PENDING 告警/重试扫描（D6 §2.4） | 未做；operations 视图已能列出 PENDING，扫描 Job 待后续批 |
| prompt 预留超时自动释放 | **刻意不做**：UNKNOWN/DISPATCHED 保持 PENDING 需人工核对，禁止超时自动退款 |
| `/zs` 页面与公司开通页 | 未做（前端） |
