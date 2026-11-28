# BIZ-T14 前端清理台账与验收证据

对应统一计划：[V3 业务工程整改计划](V3-business-engineering-remediation-20261127.md) 的 BIZ-T14、C01–C03、BIZ-A50–A53。

本任务从 `main e9cd13132d3cdae8300a0ce89f95b03349069bf3` 开发，最终重放在 `main 0624d2e514b08301d529ba7cf53e69463e0717e6`。保留已合入的 T05 原预算版本、T10 展示名、T13 历史费用和移交承接、T19 权威进度、E04 原应付归属、E03b 归档费用补齐，以及旧报销兼容、当前交接定位、节点 PDF 和材料复选框修复。清理不修改这些业务规则。

## 1. 现状与实施边界

`pages/workflow/index.js` 的线上视图已由十类白名单控制，WXML 仍带着原 AI、demo、旧通用表单和局部配置页。`pages/workflow/commission/index.js` 的业务新委托已通过 `external-entry.js` 进入外部委托，但仍打包旧提交编辑器及只允许已退役设计岗使用的行政工作台。

`sales-flow.js` 混合了历史读取与旧本地写命令。财务、消息、客户进度、工程资料仍在使用其中的读辅助，不能整文件直接删除。当前小程序以 Java API 为服务端，两个旧 Node demo 服务没有当前页面或验收脚本调用，仍存在启动命令。

本次使用现有 API、导航、模板和测试运行器，没有增加业务功能、依赖或另建委托模型。ENG-S03 的工程跳转壳单独提交；现行 `delivery/progress` 的业务写入边界由 ENG-E06 负责，本任务保留其当前分支供独立修复。

## 2. 删除、迁移和保留清单

| 对象 | 当前调用证据 | 处理与替代 |
|---|---|---|
| `pages/workflow/index.wxml` 的旧 AI、立面、案例结果、`!platformMode` 和通用 `sections` 分支 | `platformLoad` 只允许 customers/customer/arrival/transfer/requirement/budget/budget-result/contract/delivery/progress；页面使用纯 API 会话门禁 | 删除不可达模板、事件与独占样式；十类当前视图保留。旧链接显示已停用提示，可回客户列表 |
| `workflow/project-views.js`、`organization-views.js`、`task-views.js`、`budget-result.wxml` | 没有活动页面加载；旧预算模板只有被删除的本地分支引用 | 删除；`views.js` 只保留当前标题和旧链接提示名称 |
| 四张 `biz-facade-*.jpg` | 只由上述已退役模板及资产清单引用 | 删除文件和清单项。未按名称批量删除历史平面方案、案例、付款或回执图片 |
| `workflow/explore/platform-bridge.js` | 现行 `explore/index.js` 是登录、岗位校验与迁移页面跳转壳，没有加载此桥接层；三个历史集成脚本仍使用它 | 迁至 `scripts/fixtures/legacy-explore-platform-bridge.cjs`；原断言继续运行。当前迁移请求桥接的会话检查成为现行静态检查对象 |
| `utils/sales-flow.js` | 财务台账、收款、消息、工程资料、客户进度及老板页面间接消费历史辅助 | 迁至 `utils/commission-history.js`；19 个保留导出的函数或值保持原实现。所有活动引用同步改名 |
| `sales-flow` 的 `savePolicy/run/validate/transition` | 没有当前产品页面调用；旧 Node demo 使用本地写能力，人员归属测试使用 transition | 从生产模块删除；原本地业务校验明确拒绝旧委托写命令。纯 transition 迁至历史测试夹具，人员归属和原文件读取断言保留 |
| `workflow/commission` 旧 submit 编辑器、admin/projects 工作台 | 业务 `onShow` 已经转外部委托；行政工作台仅面向退役 `hq-design-admin` | 删除旧表单、编辑事件、工作台分页/统计/待办状态、独占组件及样式；原单读取、回执、历史费用复核、接收/退回、附件和委托单导出继续保留 |
| `commission/presentation.js` 工作台映射、旧 submit 字段；`design-brief.js` 草稿编辑和继承方法 | 当前历史页只需 DTO、原表单和冻结资料的读取 | 删除无消费者的旧编辑/工作台辅助，保留历史快照、摘要、字段和导出读取。旧 submit 命令在页面明确拒绝 |
| `api/franchise/commission.js` 的 submit、workbenchCounts、workbenchRecent | 清理后无产品消费者 | 删除包装；保留原详情、历史回执/结算、附件和命令包装。分页 `page` 仍被业务、老板摘要使用，完整保留 |
| `commission-feedback/design-complete/design-dispatch/fee-decide` 编辑分支 | T13 服务端仅允许已提交成功的原键回读；`allowedActions` 不再发出这些新命令 | 删除字段、表单、事件、命令组装及 `feeDecide` 专属 API 包装。旧编辑链接按原 ID 只读展示，旧服务端提示不能重新打开表单；历史反馈、完成、下发和费率字段继续读取 |
| 原 policy/pricing 编辑器 | 老板设置页仍有 policy 入口；pricing 仅旧设计岗入口；新 LEGACY 已停写，没有新费用业务消费者 | 复用原 current 接口及原权限改为只读规则展示；移除保存方法和 API 包装。旧规则与原单冻结费用版本不删。服务端规则 POST 的收口归 T15，本前端任务未修改服务端 |
| `commission/journey.wxml`、`journey.wxss`、`simulated-cashier.wxml`、`payment-flow.js` 和四张独占支付图 | 当前页未导入 journey/cashier 模板；现金台旧事件已无绑定，图片只在死模板和历史包测试中使用 | 删除模板、样式、无消费者的空拒绝事件及图片；保留 `simulated-payment.wxml` 的真实历史回执、费用复核、原财务入口。测试改验实际回执和原 pay/paid 深链 |
| `server/design-collaboration/`、`server/management/` | 仅内部 demo 相互引用，无现行页面、测试工厂/runtime 消费者 | 删除实现和独占启动命令；Git 历史可恢复。没有删除测试文件来掩盖依赖 |
| `server/wallet/server.cjs` | `verify-recharge-flow.cjs` 仍有真实调用 | 保留测试服务，仅删除无消费者的独立 start 脚本 |

共享调用关系：`finance-ledger/finance-desk/design-receipts` 继续使用 `commission-history` 的费用授权、历史状态和附件辅助；`owner-business` 继续通过 `engineering-records` 读取原工程关联；`finance-ledger → engineering-records.financialTasks` 和 `build/record/index → engineering-records.applyTemplate` 未在本任务删除。

## 3. 旧链接与历史数据的当前落点

| 原入口 | 清理后的行为 |
|---|---|
| 业务岗位的委托 `view=submit`，带 customerId 或旧 id | 继续走原 `external-entry.js`，按客户 ID 打开现有外部委托；不先读取旧计价、预算或草稿 |
| 业务岗位的委托 `view=submit`，带 commissionId | 按同一不透明委托 ID 打开现有外部委托，不转换成客户 ID |
| 委托 `view=admin/projects` | 提示旧工作台已停用，能回当前岗位工作台；不加载退役设计待办或统计 |
| 未登录的历史原单链接 | 清空原数据并提供登录动作；return 参数保留原 view、commissionId/customerId 和 history |
| 授权历史委托、回执、成果附件 | 继续请求原接口和原 ID；冻结需求、预算版本、费用版本、原附件及委托单导出保留 |
| 缺少旧委托的非业务 submit 链接 | 显示缺少历史原单与返回入口，不出现可编辑旧新增表单 |
| 旧移交只允许退回 | T13 的 `packet-return` 权限、原因字段和原命令保留；历史只读模式仍禁写 |
| 旧 feedback/complete/dispatch 链接 | 按同一原委托只读展示，保留历史意见和成果；不再恢复设计编辑表单 |
| 原 policy/pricing 链接 | 沿用原读权限查看规则版本、金额、范围和主体；没有保存控件或新规则请求 |
| 旧设计/explore、历史结果与历史预算 | 保留原兼容壳、岗位门禁、固定结果/版本和迁移页路径，不重新开放 AI 生成 |

未删除旧 storage 记录，也没有把旧模拟记录计为实际付款。历史费用复核继续使用原委托版本、原请求正文和幂等键；服务端拒绝访问时仍清除旧金额、说明和待重试命令。

## 4. 检查迁移与基线说明

旧设计工作台测试本来验证的是已经退役的岗位，不能继续作为当前可用功能验收。原分页、乱序、失败重试、旧提交、模拟退役及草稿继承断言保存在：

- `scripts/historical/verify-commission-pagination.cjs`
- `scripts/historical/verify-commission-submit-lifecycle.cjs`

它们要求显式指定清理前的 Git 工作树，避免误当现行命令。对独立 `e9cd1313` 工作树执行结果为 **37 通过、0 失败、0 跳过**：

```bash
ZS_LEGACY_WORKTREE_ROOT=/path/to/pre-t14-worktree \
  node --test scripts/historical/verify-commission-pagination.cjs \
  scripts/historical/verify-commission-submit-lifecycle.cjs
```

当前 `check:business-repair` 改为执行 `verify-legacy-commission-entry.cjs`，覆盖实际旧链接、权限、登录返回、冻结原单读取及禁止新 LEGACY 写入。当前六角色生命周期改为验证旧移交原 ID、会话迟到隔离和 T13 历史费用办理。原设计修复脚本的退役模拟断言改验旧提交全部拒绝；原工程接收、历史委托单导出、付费结果恢复和归档分页断言保留。

补清前另运行原收银台/包专项，14 项通过，确认基线后把旧模板的图片断言替换为实际历史回执的原编号、版本、金额、无新付款动作断言。当前六角色协议不再模拟新设计下发，改验退役命令拒绝及真实旧移交的附件 ID、原下发版本和接收人 ID。长文本检查改验仍活跃的委托单原字段，保留换行与布局断言。

清理前另有两个已存在的坏检查：共享工具脚本读取早已不存在的 `build/model.js`；存储键脚本仍要求已退役 demo 键。已先记录基线失败，再改验实际活跃的 `engineering-records` 严格日期/金额消费和现行 19 个存储键。日期范围、金额精度及 `-0` 行为断言保留。

## 5. 完成标准和自动化结果

| 验收项 | 本地证据与结果 |
|---|---|
| A50：真实入口、动态路由、事件、共享引用与启动命令 | 上述逐项调用证据；活动源码无被删模块/模板引用。共享读取迁移及第一批模板清理由独立只读评审确认 |
| A51：灵感库、免费预算、收藏、客户上下文、原版本保存 | `npm run check` 的设计、免费预算、客户上下文、版本和归档回归通过；迁移设计分包未改动 |
| A52：旧深链、回执、历史资产和原单承接 | 新深链专项 13/13；补清的原单/规则/收银台/包/六角色专项 53/53；最终重放 main 后原单/六角色/外部委托/交接 70/70；当前委托修复 8/8；退休历史断言 37/37 |
| A53：包、依赖与悬空资源 | `npm run check`、`npm run check:role-homes`、`npm run check:packages` 通过；锁文件及依赖未变；129 个 WXML 完整性检查通过 |

仓库包核算（源文件与打包忽略规则口径，不是微信上传回执）：workflow 分包从 **1,918,698 字节**降至约 **1,275,665 字节**；主包从 **1,957,409 字节**降至约 **1,944,489 字节**；`design-migration` 分包保持 **712,780 字节**。各包约束及预留空间检查通过。

## 6. 发布与人工验收清单

- [x] 当前 API 页面与历史只读辅助仍有有效消费者和回归证据。
- [x] 旧提交/旧工作台不再渲染或产生新 LEGACY 命令。
- [x] 原历史费用复核、旧包退回/接收、附件及导出保留。
- [x] 包资源、当前完整检查、角色主页与 WXML 检查通过。
- [x] 原历史测试对原 Git 版本可运行，当前验收命令已有实际功能替代覆盖。
- [ ] 父代理对最终 C02 diff 独立评审、远程 PR/CI 与合并回执。
- [ ] 在目标环境用当前账号验证灵感收藏、免费预算原版本、历史回执/附件、旧委托返回和当前岗位工作台。
- [ ] 微信开发者工具及真机视觉检查长文本、预算表单、历史委托单导出；记录前后端配对 SHA。

静态引用、Node 页面/WXML 集成与仓库包核算不能替代线上流量、目标环境权限和微信真机证据；本文件不把这些环境项记为已验收。
