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

## 评审 R2 修复（幂等模型重构）

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-a | 收据冻结阻断正常重试（NOT_SENT→SUCCEEDED / UNKNOWN→SUCCEEDED 必冲突） | 收据改为**单调推进**：NOT_SENT/UNKNOWN/DISPATCHED→SUCCEEDED 允许；仅"已成功换 hash"或"成功后回退"才冲突；无收据行允许后续补齐 callId |
| P1-b | 重试 operation 身份未贯穿（成功后重放会重复扣；`:r1` 重放被拒；r1 完成后 finish 回落旧 operation） | 统一 `reservePromptPool`/`finishPromptPool`：按"链路=base 或 base:rN"判定；**链内有 PENDING 或 SETTLED → 直接重放返回**；仅全链 RELEASED 才新建 `:rN`；finish 在途优先（含旧 attempt 的未决，允许新持有者确认） |
| P2 | 旧 operation 确认后绕过 hash 冲突 | 收据校验前置到任何早退之前（callId/hash/outcome 单调规则），确认旧 operation 后仍拒不同 hash |

复跑：`AiJobOrgChargeRoutingTest` **14/14**（新增成功重放不重复扣、`:r1` 重放单 operation、确认旧 operation 后 hash 冲突）；ai-orchestration **72/72**；commerce orgpoint **61/61**。

## 评审 R3 修复

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-a | `design_prompt_receipt.call_id` NOT NULL，四参数（无收据）路径落库失败 | 列改为可空（无收据是合法路径）；补真实 DB 测试（null callId → 后续带收据升级） |
| P1-b | 新持有者确认旧 operation 后，同 attempt 再 reserve 会重复预扣 | 收据带 `operationId`；reserve 先查该 attempt 收据指向的 operation 若已 SETTLED → 直接返回 ORG_RESERVED |
| P1-c | "未成功收据已有 hash 与成功 hash 不同"被误拒 | 同一场景由 R2 单调规则覆盖（仅成功冻结），本轮已核对无需变更 |

复跑：`AiJobOrgChargeRoutingTest` **15/15**（新增确认旧 operation 后 reserve 不重复扣）；`DesignBillingBindingIntegrationTest` **6/6**（null callId 真实落库）；ai-orchestration **73/73**。

## 评审闭环

| 轮次 | 结论 | 处置 |
|---|---|---|
| R1 | FAIL（2 P1 + 3 P2） | 跨 attempt 重投 / NOT_SENT 重试伪预留 / my-usage 门禁 / 收据缺失 |
| 修复 | `cf261652` | 未决禁重投、重试新 operation、my-usage 去门禁、收据持久化 |
| R2 | FAIL（2 P1 + 1 P2） | 收据冻结阻断正常重试 / 重试身份未贯穿 / 确认旧 operation 绕过 hash |
| 修复 | `9eee0755` | 收据单调推进 + 链路幂等 + 收据校验前置 |
| R3 | FAIL（2 P1） | call_id 非空导致无收据路径落库失败 / 确认旧 operation 后 reserve 重复扣 |
| 修复 | `2be5ec0c` | call_id 可空 + 收据带 operationId 且已结算则该 attempt 重放不扣 |
| R4 | **PASS** | 极窄终审核销，无连带矛盾 |

## D6 §2.4 告警 Job 评审修复

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1 | 扫描固定取最旧 100 条，后续组织永不告警 | 改为 `pendingAlertSummary`：**按组织全量聚合**（total/byOrg 覆盖全部超阈值记录），样本单独限 20 条仅用于定位；新增 120+1 条跨组织回归 |
| P2-1 | 阈值无范围校验（配 -1 会把新记录也报出） | 服务层校验 `1..720` 小时，越界抛错（Job 捕获后跳过本轮并告警日志） |
| P2-2 | 人工 settle 接受 0/负数并按 1 入账 | 端点 `@Min(1)` + 类 `@Validated`，零交付必须走 release |

| P2-2 | 汇总三次查询非同一快照（total=1 但 byOrg 为空） | 汇总整体包进 **REPEATABLE READ 只读事务**（`snapshotTx`）；R3 指出外层事务会使其失效 → 改 **REQUIRES_NEW**；R4 PASS |

复跑：`OrgPointOnboardAndViewIntegrationTest` **8/8**（新增跨组织全覆盖、阈值越界）；orgpoint 相关 24/24；commerce 全套 64/64；全仓 compile 通过。

## D6 §2.4 告警 Job 评审闭环

| 轮次 | 结论 | 处置 |
|---|---|---|
| R1 | FAIL（1 P1 + 2 P2） | 截断漏组织 / 阈值无校验 / settle 收 0 负数 |
| 修复 | `eb2c5410` | 按组织全量聚合 + 阈值 1..720 + `@Min(1)` |
| R2 | FAIL（1 P2） | 三次查询非同一快照 |
| 修复 | `a77f0190` → `5e49090d` | RR 只读 + **REQUIRES_NEW** |
| R4 | **PASS** | 极窄终审核销 |

## 四、未做/边界

| 项 | 说明 |
|---|---|
| design 长期 PENDING 告警/重试扫描（D6 §2.4） | **已做告警与人工核对端点**（`OrgPointPendingAlertJob` + `/org-point-operations/{id}/settle|release`）；自动重试仍不做（UNKNOWN 禁自动退款） |
| prompt 预留超时自动释放 | **刻意不做**：UNKNOWN/DISPATCHED 保持 PENDING 需人工核对，禁止超时自动退款 |
| `/zs` 页面与公司开通页 | 未做（前端） |
