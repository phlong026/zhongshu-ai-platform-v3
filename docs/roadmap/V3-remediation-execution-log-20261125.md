# 众墅之家 V3 V1.1 整改执行日志

> 执行依据：`docs/roadmap/V3-module-remediation-plan-20261125.md`
> 执行仓库：`phlong026/zhongshu-ai-platform-v3`
> 建立日期：2026-11-25（北京时间）
> 本文件记录实际执行证据；不替代 V1.1 任务定义，不把 PR 合并等同业务验收。

## 1. 执行规则

1. 按 V1.1 的 13 个执行节点顺序推进；无依赖的子任务可并行，但不得跳过前置契约。
2. 每个子任务先审计当前代码、数据库、接口、权限及调用链，再评估开源复用。
3. 按 TDD 先建立失败测试，再实现代码。
4. 子任务独立开发、专项评审、独立 Commit；同一模块形成模块 PR。
5. 小程序改动进入 `main`；Java 后端/Vue 管理台改动进入 `backend-main`。跨端任务建立配对 PR。
6. PR CI 全绿、专项评审通过后合并；合并后继续检查主分支 CI、前后端精确 SHA 配对、数据库迁移和业务场景。
7. 只有代码、评审、CI、配对、数据库及业务验收全部通过，才标记“已完成”。
8. 不执行未经授权的生产账务操作、真实支付消费、生产数据清理或生产部署。

## 2. BASE 基线审计记录

### 2.1 仓库基线（2026-11-25）

| 项目 | 当前值 | 证据 |
|---|---|---|
| 小程序主线 | `main` / `6543e180013cfd803eabb535e2f9883e700f7f55` | GitHub branches/main |
| 后端主线 | `backend-main` / `27afed9127f06d2625d8b47af568edf3bccc40d5` | GitHub branches/backend-main |
| 开放 PR | 0 | GitHub pulls?state=open |
| #171 | 已合并至 main | merge SHA `2447bde9bf3c0a1832bd37d5df5538f93d12d227` |
| #172 | 已合并至 main | merge SHA `6543e180013cfd803eabb535e2f9883e700f7f55` |
| #173 | 已合并至 backend-main | merge SHA `27afed9127f06d2625d8b47af568edf3bccc40d5` |
| #166 | 已合并至 backend-main | merge SHA `762efc68de328859ab593a88a27f78b968f0b5b9` |
| #168 | 已合并至 main | merge SHA `5aee021fab34e7f653d63d448909661e0aa470d4` |
| #84 | 已合并至 main | 按 V1.1 转为业务回归，不重复开发 |

以上 SHA 是本次执行基线快照；每个新 PR、配对验收及发布候选均须重新读取完整 SHA。开放 PR 数量 0 是创建本执行日志 PR #175 之前的快照；#175 为本日志自身的文档 PR，不属于 V1.1 业务整改开放项。

### 2.2 CI 与配对状态

| 分支 | 工作流 | SHA | 本次核查结果 |
|---|---|---|---|
| main | Frontend CI | `6543e180013cfd803eabb535e2f9883e700f7f55` | success |
| main | Paired frontend/backend verification | `6543e180013cfd803eabb535e2f9883e700f7f55` | success（run 45631391774） |
| backend-main | Backend CI | `27afed9127f06d2625d8b47af568edf3bccc40d5` | success |
| backend-main | Paired release rehearsal | `27afed9127f06d2625d8b47af568edf3bccc40d5` | success |

- Frontend CI 使用 Node.js 24，执行 `npm ci`、`npm run check`、`npm run check:role-homes`、`npm run check:packages`。
- Backend CI 使用 JDK 17、PostgreSQL 17；发布回归还包含 Redis 7。
- 后端构建入口为 `平台程序/script/platform/build-platform.sh`，不得用跳过测试的构建替代正式门禁。
- 两条主分支当前 GitHub API 返回 `protected=false`。在分支保护未配置前，执行中严格人工执行 PR/CI 门禁；不得因 API 允许直接推送而绕过流程。

### 2.3 代码与数据库资产盘点

| 资产 | 盘点结果 | 说明 |
|---|---:|---|
| 后端平台目录文件条目 | 7,592 | Git tree 静态盘点 |
| 平台基础能力模块 | 7 | platform-command/org/workflow/authz/identity/wallet/test-support |
| 加盟业务模块 | 12 | franchise-api/test-support/org/crm/commission/finance/design/sales/asset/content/engineering/performance |
| 正向 SQL 迁移文件 | 232 | 全后端模块静态计数 |
| 回滚 SQL 文件 | 146 | 目录内 migration-undo 静态计数 |
| 后端测试文件 | 501 | Java/JS/Python 等测试文件静态计数 |

后端 README 明确要求保留已发布 Flyway 迁移；修复应追加新迁移，不得改写已经应用的迁移。真实数据库当前版本、迁移执行状态和备份恢复能力仍需在 QA 节点通过隔离环境核验。

### 2.4 开源复用评估

- 已核对官方 [YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro) 及 [Yudao 官方文档](https://doc.iocoder.cn/intro/)。
- 当前后端 POM 已基于 Yudao，且已有独立平台能力层和加盟业务域层；现有底座覆盖系统权限、租户、工作流、支付及基础设施能力。
- 执行策略：优先复用当前仓库的 Yudao 模块、platform 模块和 franchise 领域模块；仅当某个子任务存在明确能力缺口时，再评估外部开源组件。禁止为整改而引入另一套 CRM/ERP/支付/工作流平台。
- 各子任务仍需进行针对性开源检索并记录“复用/不复用”理由。

### 2.5 已确认的代码差异线索

**AUTH-06｜公开门店地址**

- 当前已有 `FranchiseDirectoryService` 和总部管理台加盟商名录维护入口。
- 当前已有独立的 `public_name` 字段及“公开地图名称”维护接口；迁移 `V20261125.1045__franchise_public_map_name.sql` 只增加 `public_name`。
- `FranchiseMapService.snapshot()` 当前将 `publicAddress` 固定为空字符串；当前已读迁移中未发现独立 `public_address` 字段。
- 后续应独立维护公开地址、建立服务端授权投影和测试；禁止从 `pf_org_profile.address` 或内部资料自动回填。

**CONTENT-06｜我的作品**

- 当前已发现 `ContentTaskResultService` 用于将已成功任务的结果持久化到文案/脚本记录；`ContentTaskController` 提供个人任务列表、详情和隐藏任务入口。
- 任务隐藏不等于删除作品；任务结果持久化也不等于作品库已形成完整的权威列表/删除/恢复接口。
- 具体作品数据模型、前端“我的作品”入口及后端 DTO/列表/删除/恢复能力，必须在 CONTENT-06 开始前继续追踪调用链，不得猜测接口字段。

**FIN-03A｜财务更正恢复**

- #171 已合并；PR 描述和代码差异显示它补充了财务负责人视图的“作废后继续重登”入口，沿用已有更正链，不重发作废请求。
- `scripts/verify-finance-correction-reconcile.cjs` 已覆盖回执丢失、普通作废过滤、替代单过滤、权限、换岗迟到响应、退款/支出退回分流等专项合成场景。
- 本次复核发现该测试文件此前未被 `check:finance-workbench`、`check:integration` 或 `npm run check` 调用，故新增子任务 FIN-03A-CI-01，将它接入 `check:finance-workbench`，由正式 Frontend CI 执行。
- 后端 `FinRefundThreeDirectionIntegrationTest` 已覆盖更正链 CAS、并发单赢家及支出退回更正；Backend CI 的财务模块完整 `verify` 会运行该测试。
- 未操作真实账务；微信真机和财务岗位业务验收仍需完成。不得把 #171 合并或单纯测试文件存在视为 FIN-03A 已完成。

## 3. 13 个执行节点状态

| 节点 | 执行主题 | 关联任务 | 当前状态 | 进入条件/完成标准 |
|---|---|---|---|---|
| 00 | 基线核查与任务冻结 | BASE-01～06 | 已完成 | 基线、代码差异、开源复用及执行日志 PR #175 已合并 |
| 01 | 财务更正恢复专项验收 | FIN-03A / #171 | 进行中 | 将专项回归接入 CI；随后核验精确配对、幂等、权限及两类退款 |
| 02 | 门店公开地址与总部权限 | AUTH-06 | 待开始 | 独立公开地址字段、维护入口、授权投影和隔离测试 |
| 03 | 工程付款、到货与交付验收 | ENG-02/03/04 / #166/#173 | 待开始 | 五期付款、净收款、待补到货、真实到货、交付凭据回归 |
| 04 | 发布链接与客资关联验收 | CONTENT-01～04 / #164/#168/#155/#84 | 待开始 | 弱网、回执丢失、重复提交、客户触点归因 |
| 05 | 历史 PR 差异核查 | #26/#27/#106/#107 | 待开始 | 不重新合并退役 PR；形成主干覆盖与独有差异证据 |
| 06 | 我的作品功能补齐 | CONTENT-06A/B/C | 待开始 | 权威 DTO、列表、删除/恢复、任务结果入作品 |
| 07 | 内容获客成交统计 | CONTENT-07 | 待开始 | 内容、触点、客户、合同和成交关联准确且去重 |
| 08 | 设计功能与快速预算收敛 | DESIGN-02/03/05 | 待开始 | 灵感库保留；旧生成入口、深链及写接口收敛 |
| 09 | 工程成本与财务账本 | ENG-04～06 / FIN-04～08 | 待开始 | 材料/劳务/费用/退款/垫付账本不重复，现金流与毛利分离 |
| 10 | 客户、委托、合同、工程全链路 | CRM / ENTRUST / ENG | 待开始 | 八步、外部成果、合同、工程接收和历史迁移回归 |
| 11 | 绩效、公告、导航及公司额度 | PERF / OPS / WALLET | 待开始 | 业务规则有正式依据；支付渠道、权限及真机验证 |
| 12 | 数据迁移与正式上线验收 | QA-01～08 | 待开始 | 精确 SHA 配对、迁移/回滚、真机、三家公司陪跑全部通过 |

## 4. 逐任务证据登记格式

后续每项子任务按以下格式追加，不覆盖历史记录：

| 字段 | 记录内容 |
|---|---|
| 任务编号 | 如 AUTH-06 |
| 审计基线 | 前后端完整 SHA |
| 开源复用评估 | 仓库/组件、复用结论及原因 |
| 根因与影响 | 可复现证据、业务影响 |
| 改动范围 | 页面、接口、权限、数据表、迁移 |
| 测试 | 先失败后通过；单元/集成/权限/异常/并发 |
| 专项评审 | 结论、阻断项、整改记录 |
| 分支与 Commit | 分支名、独立 Commit SHA |
| PR 与 CI | PR 编号、检查结果、合并 SHA |
| 配对验收 | 前后端精确 SHA、运行记录 |
| 数据库 | 迁移文件、升级兼容、回滚/恢复 |
| 业务验收 | 岗位、场景、预期、实际结果、证据 |
| 最终状态 | 待开发/进行中/待核查/待验收/已完成 |

## 5. 当前未完成事项

1. 节点 01：FIN-03A-CI-01 已接入 `check:finance-workbench`，等待本 PR 的 Frontend CI 实际执行；之后完成最新配对和财务岗位业务验收。
2. AUTH-06 尚无独立公开地址字段及授权展示闭环。
3. CONTENT-06 尚需完成作品模型、列表、删除/恢复的完整代码追踪。
4. ENG-02/03/04 等已合并代码仍需在最新精确前后端配对下做业务验收。
5. 当前分支保护未启用，所有合并必须人工核验 CI 与评审，不得绕过。

## 6. 节点 00 完成与节点 01 执行记录（2026-11-25）

### 6.1 节点 00 完成

- 执行日志 PR #175 已合并至 `main`，合并 SHA：`08ae5942876abe8ce5c7b794315e9cb5452fae99`。
- PR #175 仅新增本执行日志，不修改业务代码；PR Frontend CI 通过。相同业务代码对 `main=6543e180...` + `backend-main=27afed91...` 的配对验收 run 45631391774 已成功。
- 合并后 `main=08ae5942876abe8ce5c7b794315e9cb5452fae99` 的 Frontend CI run 45637055828 成功；合并后配对 run 45637054667 在本记录更新时仍在执行。该提交仅增加文档，因此原业务代码配对结果仍作为本节点代码基线证据；后续发布候选仍须使用最新 SHA 重新配对。

### 6.2 FIN-03A 专项复核

- 前端 `pendingRefundCorrections` 仅允许 `finance-lead` 看到“退款待重登”，且仅筛选 `status=已作废`、`voidReason=更正重登`、`replacedById=null` 的记录。
- 续办时重新读取 `/franchise/finance/refunds`，后端按公司返回全状态记录；页面再次确认原单仍待重登后才恢复表单。
- `correctionGuard` 检查 session、当前岗位、页面代次及页面生命周期；续办不再次调用作废接口。
- 后端沿用现有 `replacesId` 更正链及 CAS 替代权；退款与支出退回分别使用原收款/付款来源。
- 本任务不修改后端账务逻辑，不新增数据库迁移，不执行真实账务。

### 6.3 FIN-03A-CI-01

| 字段 | 内容 |
|---|---|
| 问题 | 专项回归脚本存在，但此前没有进入标准 `npm run check` 路径 |
| 修改 | 将 `node scripts/verify-finance-correction-reconcile.cjs` 加入 `check:finance-workbench` |
| 影响 | 由 `npm run check` 和 `check:role-homes` 间接执行 |
| 分支 | `fix/finance-correction-ci-20261125` |
| 状态 | 已提交代码，待 PR CI 与专项评审 |
| 数据库 | 无迁移 |
| 后端 | 无代码修改；复用现有 PostgreSQL 更正链集成测试 |

---
本文件只记录执行管理状态；不代表已部署、已操作生产数据或已通过真机/三家公司验收。
