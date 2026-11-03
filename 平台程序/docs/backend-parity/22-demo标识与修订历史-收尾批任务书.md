# 22 · demo 标识与修订历史 UI 收尾批任务书

> 批次：后端化工程 · 收尾批　日期：2026-11-03　状态：**V1（成稿，待 Codex 评审）**
> 上游：00 框架 + demo 收口拍板（2026-11-03 用户拍板：**维持现状+标识**，双体系共存零收缩）+ 21 号遗留（修订历史 UI 展开）。
> 编号：22；基线：monorepo parity=stage4-done（0c1aee7）；v3=feat/performance-api-integration-20261103（afdc08c）。**双仓库零后端改动。**

## 一、范围（两项，纯 v3）

| # | 项 | 内容 |
|---|---|---|
| F1 | demo 标识 | 新建 `utils/demo-badge.js`（唯一判定源：`isDemoMode()`=有演示身份上下文且无 API token；`badgeText()`）。降级/演示分支页面顶部渲染「演示数据（本地）· 登录后切换平台数据」标识条。**首批覆盖八个在线分流页**：build/record、build/projects、build/home、admin/tasks、admin/commission、admin/owner-page（performance Tab）、admin/finance-desk、studio——各页仅在**降级分支**渲染（API 态不渲染，不产生新请求） |
| F2 | 修订历史 UI 展开 | finance-desk 修订弹层内嵌「修订历史」段：openRevision 时并行拉 `GET /franchise/finance/revisions?receiptId=` → 弹层内倒序列表（版本/原→新金额/说明/操作时间）；冲突自动刷新版本后同步刷新历史 |

## 二、勘察（已核实）

| # | 事实 | 出处 |
|---|---|---|
| F-1 | 各在线分流页已有降级分支判定先例：P.online() 分流（performance/engineering/finance-desk）；platDegraded 标识（owner-page）；platformFinance.status==='degraded'（finance-desk） | 各页 index.js |
| F-2 | finance-desk 修订弹层已有结构（revisionForm：receiptId/baseRevisionVersion/amount/note；冲突自动刷新版本已实现——D-3 UX） | 21 号交付 |
| F-3 | 后端修订历史端点已备：GET /franchise/finance/revisions?receiptId（0f74d14 含组织校验）；api/finance.js 已有 revisions() | FinanceController:151 / api/finance.js |
| F-4 | 修订入口行 id 形如 r-123（需剥前缀取 receiptId——已实现） | platform-actions openRevision |

## 三、设计决策

| # | 决策 | 说明 |
|---|---|---|
| D-1 | 标识判定唯一源 | demo-badge.isDemoMode() = `!http.token()`（与各页 P.online() 同源反义）；API 态不渲染；不做逐页白名单 |
| D-2 | 标识样式 | 页首一行 fin/demo 通用样式（`demo-badge` 类），文案「演示数据 · 本地保存，登录后切换平台数据」；不阻塞交互 |
| D-3 | 历史数据来源 | 弹层打开时拉取（不做全局缓存）；失败显示「历史加载失败」不阻塞修订提交 |
| D-4 | 首批八页边界 | studio/growth 等纯演示页（无 API 分流）不在本批（标识无对比意义）——只覆盖有在线/降级双分支的八页 |

## 四、AC

| # | 断言 |
|---|---|
| AC1 | verify-demo-badge：badge 模块可加载/八页 wxml 均含标识条绑定/isDemoMode 与 http.token 反义/在线分支不渲染标识 |
| AC2 | verify-finance-desk-platform 补：修订历史段绑定（revisions 列表/失败文案）/弹层打开触发拉取 |
| AC3 | 真机冒烟：demo 上下文打开八页均见标识条；登录后打开均不渲染 |
| AC4 | npm run check passed（wxml 配平） |

## 五、非目标

demo 降级分支收缩（维持现状拍板）· studio/growth 纯演示页标识 · 服务端分页 · admin-ui Web 端 · finance files 历史迁移。
