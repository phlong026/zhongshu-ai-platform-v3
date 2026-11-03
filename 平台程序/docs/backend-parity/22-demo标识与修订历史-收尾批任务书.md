# 22 · demo 标识与修订历史 UI 收尾批任务书

> 批次：后端化工程 · 收尾批　日期：2026-11-03　状态：**V1（成稿，待 Codex 评审）**
> 上游：00 框架 + demo 收口拍板（2026-11-03 用户拍板：**维持现状+标识**，双体系共存零收缩）+ 21 号遗留（修订历史 UI 展开）。
> 编号：22；基线：monorepo parity=stage4-done（0c1aee7）；v3=feat/performance-api-integration-20261103（afdc08c）+ **R3 前置修复（reviseReceipt idemKey 参数遮蔽同步 TypeError + verify-finance-desk M5 段 ok(cond,name) 假绿翻转，已修并复跑 32/0）**。

## 0、修订记录

**V2（2026-11-03，Codex R1 FAIL 5P1+3P2 修复）**：①**前置 bug 修复（已落码）**：reviseReceipt idemKey 参数遮蔽（`idemKey || idemKey('revision')` 同步 TypeError——idemKey 是字符串）改为 `idemKey || ('fin-revision-'+receiptId+'-'+Date.now())`；verify-finance-desk-platform M5 段 ok('名',cond) 参数顺序假绿全部翻转修正并改真实条件（含 reviseReceipt 非 async 修正）。②**P1-2 八页边界按实读修正**：studio 保留（有任务列表/作品回读/发布回执 API 分支，非纯演示页）；owner-page 工厂改为 admin/performance/index 实页；build/home 标注混合页（PTPage 待办+工程计数为在线区块，主体 W.home() 本地）。③**P1-1 判定源含实际展示来源**：isDemoMode 不只看 token——降级标志（__platDown/platDegraded/platformFinance.status==='degraded'）或无 token 均视为演示展示；API 态正常不渲染；状态矩阵入 AC。④**P1-3/P1-4 F2 契约明确**：前置修复 reviseReceipt 遮蔽已落码；冲突按错误码 1102000003 识别（弃文案 indexOf）；冲突后**回读当前收款+历史**刷新基准版本与原金额（不只 +1）；AC 覆盖跨多次修订。⑤**P2-5 假绿**：verify M5 断言顺序翻转修正+改真实函数条件（reviseReceipt 非 async）。⑥**P2-6 状态矩阵**：游客(无身份无token→不渲染)/有效demo身份无token(→渲染)/有效API(token→不渲染)/有token但降级(→渲染)。⑦**P2-7 字段契约**：修订历史展示=baseRevisionVersion（"修订后版本"=自身）、originalAmountCents/newAmountCents 分转元、createTime ISO 本地月日；后端已按 createTime DESC 返回——不再 reverse。⑧**P2-8 历史加载行为**：打开即拉取/每次打开清空上次/区分 loading·empty·failed/失败不禁用提交/切单或关闭丢弃迟到响应。⑨**P3 唯一文案**：「演示数据 · 本地保存，登录后切换平台数据」。⑩AC1 补页面返回/身份切换标识重算与标识零请求断言。⑪AC2 脚本指 verify-finance-desk-platform（已修假绿）。

## 一、范围（两项，纯 v3）

| # | 项 | 内容 |
|---|---|---|
| F1 | demo 标识 | 新建 `utils/demo-badge.js`（唯一判定源：`isDemoMode()`=有演示身份上下文且无 API token；`badgeText()`）。降级/演示分支页面顶部渲染「演示数据（本地）· 登录后切换平台数据」标识条。**首批覆盖八个在线分流页**：build/record、build/projects、build/home、admin/tasks、admin/commission、admin/owner-page（performance Tab）、admin/finance-desk、studio——各页仅在**降级分支**渲染（API 态不渲染，不产生新请求） |
| F2 | 修订历史 UI 展开（**前置 bug 已修**：reviseReceipt idemKey 参数遮蔽同步 TypeError + verify M5 段 ok(cond,name) 假绿，均已落码复跑 32/0——R1 P1-3/P1-5） | finance-desk 修订弹层内嵌「修订历史」段：openRevision 时并行拉 `GET /franchise/finance/revisions?receiptId=` → 弹层内列表（修订后版本/原→新金额分转元/说明/本地时间；后端已 DESC 不 reverse）；冲突按码 1102000003 识别并回读刷新 |

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
| D-1 | 标识判定（R1 P1-1 修正：含实际展示来源） | demo-badge.isDemoMode(pageState) = **无 token**（未登录后端，整页本地）或 **pageState.degraded===true**（API 失败已回退本地展示：__platDown/platDegraded/platformFinance.status==='degraded'）。API 正常态不渲染。demo 身份（X.context().active 且无 token）也渲染——文案不含「未登录」避免误导（R1 P2-6 状态矩阵：游客不渲染/有效demo身份无token渲染/有效API不渲染/有token但降级渲染） |
| D-2 | 标识样式与唯一文案 | 页首一行 `demo-badge` 类，文案唯一=「演示数据 · 本地保存，登录后切换平台数据」（R1 P3：已登录 demo 身份亦渲染，不误导未登录） |
| D-3 | 修订历史契约（R1 P1-4/P2-7/P2-8） | 打开即拉取、每次打开清空上次；区分 loading/empty/failed 三态（失败不禁用提交）；切单或关闭丢弃迟到响应（请求代次）；冲突（1102000003）回读当前收款+历史刷新 baseRevisionVersion 与 originAmount（不只本地+1）；字段=baseRevisionVersion（展示「修订后版本」=自身）/originalAmountCents/newAmountCents 分转元/createTime ISO 本地月日；后端已 createTime DESC 返回不再 reverse |
| D-4 | 首批页边界（R1 P1-2 按实读修正） | build/record、build/projects、build/home（混合页：PTPage+工程计数在线区块）、admin/tasks、admin/commission、admin/performance/index（owner-page 工厂实页）、admin/finance-desk、studio（有任务列表/作品回读/发布回执 API 分支——不剔除）。growth 等纯演示页不在本批 |

## 四、AC

| # | 断言 |
|---|---|
| AC1 | verify-demo-badge：badge 可加载/八页 wxml 均含标识条绑定/isDemoMode 状态矩阵四态（游客不渲染/demo身份无token渲染/有效API不渲染/degraded渲染）/标识零请求/页面返回与身份切换重算 |
| AC2 | verify-finance-desk-platform：M5 断言顺序正确（ok(cond,name)）+修订历史段绑定（loading/empty/failed 三态+字段映射）/前置修复回归（reviseReceipt 非遮蔽+verify 假绿清零） |
| AC3 | 静态冒烟：demo 上下文（active 无 token）八页渲染标识；有效 API 态八页不渲染标识 |
| AC4 | npm run check passed（wxml 配平）+ reviseReceipt 同键重放 Node 复核 |

## 五、非目标

demo 降级分支收缩（维持现状拍板）· studio/growth 纯演示页标识 · 服务端分页 · admin-ui Web 端 · finance files 历史迁移。
