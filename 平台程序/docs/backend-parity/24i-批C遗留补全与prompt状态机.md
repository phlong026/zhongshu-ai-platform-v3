# 24i · 批 C 遗留补全（岗位权限码 + AI_PROMPT 目录 + prompt 独立状态机）

> 输入：24 号评审遗留 P2（老板视图缺 `@post.has`）；D6 v2 §2.2/§2.3-B（`AI_PROMPT` 未注册目录、prompt 需独立状态机）。
> 状态：**已实施，待 Codex 评审**。

## 一、交付

| # | 交付 | 文件 |
|---|---|---|
| 1 | 岗位权限种子 `franchise:org:point:query/write`（菜单 710900 段，role_menu 714600 段；boss/owner 双授） | `franchise-system/V20261113.859`（+undo） |
| 2 | `/platform/org-point/*` 全部端点加 `@post.has` 门禁（读=query、额度/权限写=write），保留 `requireBoss` 纵深防御 | `OrgPointViewController` |
| 3 | `BillingPriceCatalog` 注册 `ai_prompt`（1 积分/次，v1） | `BillingPriceCatalog` |
| 4 | 端口补 `PoolBinding findBinding(...)` / `Long findOperationByBizKey(...)` / prompt 未决查询与收据写入 | `OrgPointPort` + `OrgPointService` |
| 4b | **长期 PENDING 告警**：每小时扫描超阈值未终结预扣，汇总一条审计事件（不自动退款）+ 人工核对端点 settle/release | `OrgPointPendingAlertJob`、`OrgPointAdminController` |
| 5 | **prompt 独立状态机（公司池 job）**：reserve→`ORG_RESERVED`；SUCCEEDED→settle(1)；NOT_SENT→release；DISPATCHED/UNKNOWN→保持 PENDING；幂等键 `design:prompt:{jobId}:{attempt}:{token}`；收据校验（callId=plan-{jobId}，SUCCEEDED 必带 sha256） | `AiJobSettlementService.reservePromptCall/finishPromptCall`（含带收据重载） |

## 二、语义要点

- **路由判据**：job 存在 `design_billing_binding`（ai_flat/ai_elevation）即公司池；否则 `prompt_pricing_version=0` 返回 `EXEMPT`（历史/App 行为不变）。
- **独立三态**：prompt 与图片 job 终态解耦——图片 FAILED 不释放 prompt 预留（任务书红线）；UNKNOWN 保持 PENDING 交人工核对。
- **幂等**：同一 `(jobId, attemptNo, token)` 重复预留命中既有 operation，不重复扣点；重放 finish 由池侧终态互斥保护。
- **鉴权双层**：`@post.has` 控制"当前岗位是否有此功能"，`requireBoss` 控制"是否本组织老板"；平台调点/迁移仍走 yudao 权限码。

## 三、验收

- `AiJobOrgChargeRoutingTest` **8/8**（新增：prompt 成功扣 1 次、NOT_SENT 释放、UNKNOWN 保持 PENDING、非公司池 job EXEMPT）。
- ai-orchestration 全套 / commerce orgpoint 全套 / 全仓 compile 结果见提交前复跑记录。

## 评审 R1 修复

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1 | UNKNOWN/DISPATCHED 后新 attempt 可再次预留（旧调用可能已执行） | `findPendingPromptOperations(jobId)`：存在未决 prompt 时只允许同键重放，新 attempt **抛错禁重投**；finish 允许当前 fencing 持有者确认旧 operation |
| P1-2 | NOT_SENT 后同 attempt 重试伪报已预留（旧 operation 已 RELEASE，成功结算会冲突） | 重试检测该键已有 operation → 生成新键 `...:r{n}` 并真实预扣 |
| P2-1 | `/my-usage` 被老板专属 query 门禁误挡普通员工 | 去掉该端点 `@post.has`，保留 `currentPost.require()`（任务书：全部在岗员工可访问） |
| P2-2 | 带收据重载接受非法 outcome 并返回成功 | 两处重载共用四态白名单 |
| P2-3 | 成功收据未持久化、hash 冲突未校验 | 新表 `design_prompt_receipt`（V20261113.314+undo）+ `savePromptReceipt` 冲突即拒 |

复跑：`AiJobOrgChargeRoutingTest` **11/11**（新增 NOT_SENT 重试新 operation / UNKNOWN 禁重投 / 非法 outcome / hash 冲突）；ai-orchestration 全套 **69/69**；commerce orgpoint 全套 **61/61**；全仓 compile 通过。

## 四、未做/边界

| 项 | 说明 |
|---|---|
| design 长期 PENDING 告警/重试扫描（D6 §2.4） | **已做告警与人工核对端点**（`OrgPointPendingAlertJob` + `/org-point-operations/{id}/settle|release`）；自动重试仍不做（UNKNOWN 禁自动退款） |
| prompt 预留超时自动释放 | **刻意不做**：UNKNOWN/DISPATCHED 保持 PENDING 需人工核对，禁止超时自动退款 |
| `/zs` 页面与公司开通页 | 未做（前端） |
