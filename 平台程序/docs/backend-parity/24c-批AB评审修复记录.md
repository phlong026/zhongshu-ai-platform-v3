# 24c · 批 A/B 代码评审 R1 修复记录

> 输入：批 A 评审 FAIL（4 P1 + 5 P2）、批 B 评审 FAIL（5 P1 + 5 P2）。本文逐条对应处置。

## 批 A（P1）

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1 | 未建池赠点 → UPDATE 0 行仍写流水（账实不一致） | `grantOnboardOnce` 先 `ensurePoolAccount` 并**校验 UPDATE 恰 1 行**，否则抛异常回滚 |
| P1-2 | 预扣/结算接受金额参数变化重放 | 预扣重放比对 `quantity`（快照）+ actorPostId；结算重放比对 `actualQuantity`；不一致 → `1_072_000_014` |
| P1-3 | content 按预估时长结算，不退差 | `deliveredUnitsOf`：优先取结果快照 `durationSec`/`outputSeconds`，缺失才回退预估；测试断言 `eq(12L)` |
| P1-4 | 终态后结算失败永久悬空 | `fr_content_task.pool_biz_key`（迁移 V20261113.814）+ `findTerminalWithPendingPool` + `ContentTaskJob.reconcilePendingPool()` 幂等重试 |

## 批 A（P2）

| 编号 | 问题 | 修复 |
|---|---|---|
| P2-1 | 终态快照混入现价 | `terminalSnapshot` 复制原 RESERVE 快照全部价格字段，只追加 actualQuantity/actualCredits |
| P2-2 | 整数溢出 | `MAX_QUANTITY=1e9` 上限校验（目录与快照换算两处） |
| P2-3 | 月界时间未统一 | 预扣一次取 `month`/`now`，额度校验与入账共用同一账期 |
| P2-4 | 调点审计必填未落实 | `adjustBalance` 强制 adminUserId 非空 + note 非空；赠点 actor 记 0（系统） |
| P2-5 | 测试可能掩盖错误 | 终态测试断言具体错误；新增调点意图冲突/未开池两例；content 断言实际交付量 |

## 批 B（P1）

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1 | 开通事务失效（事件在事务外） | 事件发布移入 `OrgOnboardService.onboard(...,designEnabled,initialCredits)` **@Transactional 内**，池失败回滚整个开通 |
| P1-2 | 零赠点首次决策缺失 | 监听器条件改为 `designEnabled OR initialCredits>0` 都建池 |
| P1-3 | 调点重放未校验意图 | 同 bizKey 比对 orgId + credits，不一致拒绝 |
| P1-4 | 调点隐式开池 | 调点要求池已存在，否则 `1_072_000_010`；不得绕过开通流程 |
| P1-5 | 月用量/员工明细契约缺失 | `PoolSummary` 增 month+monthUsedTotal（组织当月全员用量）；新增 `MyUsage`（汇总+额度+明细分页），`/my-usage` 改用它 |

## 批 B（P2）

| 编号 | 问题 | 修复 |
|---|---|---|
| P2-1 | 分页 offset 溢出 | `pageSize`/`offsetOf` 钳制 + long 偏移（page≤1e6） |
| P2-2 | 老板接口缺 `@post.has` 权限码 | **未修**：需新增 `franchise:org:point:query/write` 岗位权限种子并绑定 boss/owner（涉及菜单 id 与角色模板迁移），登记为批 C 后续项 |
| P2-3 | 审计字段 | 同上批 A P2-4 |
| P2-4 | 缺表环境 undo 失败 | undo 增加 `to_regclass` 守卫（与正向对称） |
| P2-5 | 验收测试覆盖不足 | 新增调点意图/未开池回归 + content 实际交付量断言；控制器层与分页边界测试登记后续 |

## 复跑

- commerce orgpoint 全套 **30/30 绿**（含新增 2 例）；
- franchise-content **3/3 绿**（实际交付量断言）；
- platform-authz `OrgOnboardIntegrationTest` **7/7 绿**（事件入事务后无回归）；
- 全仓 `compile` 通过。

## R2 复审补充修复（f30abd5b 之后）

| 编号 | R2 发现 | 修复 |
|---|---|---|
| A-P1-4（复现） | `markPoolBizKey` 在任务 INSERT **之前**调用 → UPDATE 0 行，键未落库，重试扫描漏选 | 改为 `insertWithPoolKey`：先 INSERT 再落键，且 UPDATE 必须恰 1 行否则抛错；五个创建入口全部改序 |
| B-P1-5（复现） | my-usage 控制器改动未随 f30abd5b 提交 | 本次一并提交（`/my-usage` 返回 `MyUsage` 汇总+额度+分页明细） |

复跑：content 11/11 绿；全仓 compile 通过。

## 遗留（未修，需评审确认）

1. **P2-2 岗位权限码**：老板视图当前仅 `FranchiseRoles.isBoss` 门禁；补 `@post.has` 需要新增权限种子与角色模板绑定（批 C 一并做）。
2. **批 A P2-5 剩余**：控制器层（Spring 事务代理/事件发布器）集成测试未覆盖，当前以服务层+真实 PG 覆盖。
3. 批 C 设计域接入（ChargeRouter）与批 D 退役/迁移**尚未实施**。
