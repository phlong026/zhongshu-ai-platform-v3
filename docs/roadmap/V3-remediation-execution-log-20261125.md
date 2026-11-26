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

- #171 已合并；代码补充了财务负责人视图的“作废后继续重登”入口，沿用已有更正链，不重发作废请求。
- `scripts/verify-finance-correction-reconcile.cjs` 覆盖回执丢失、普通作废过滤、替代单过滤、权限、换岗迟到响应、退款/支出退回分流等场景。
- 复核发现该测试此前未进入 `npm run check` 的正式链路；现将其接入 `check:finance-workbench`。
- 后端 `FinRefundThreeDirectionIntegrationTest` 已覆盖更正链 CAS、并发单赢家及支出退回更正，后端财务模块 CI 负责执行 PostgreSQL 集成测试。
- 未操作真实账务；微信真机和财务岗位业务验收仍需完成。不得把 #171 合并或专项脚本单独通过视为 FIN-03A 已完成。

## 3. 13 个执行节点状态

| 节点 | 执行主题 | 关联任务 | 当前状态 | 进入条件/完成标准 |
|---|---|---|---|---|
| 00 | 基线核查与任务冻结 | BASE-01～06 | 已完成 | 基线、代码差异、开源复用及执行日志 PR #175 已合并 |
| 01 | 财务更正恢复专项验收 | FIN-03A / #171 / #195 | 待业务验收 | 专项回归已接入 CI；最新主干配对已通过；待财务岗位真实业务验收 |
| 02 | 门店公开地址与总部权限 | AUTH-06 / #196 | 已合并，待业务验收 | 独立地址字段、授权维护、地图投影和隔离测试已合并；待迁移部署及总部账号验收 |
| 03 | 工程付款、到货与交付验收 | ENG-02/03/04、ENG-IDEM-01～05 | 进行中 | #199 已合并，#202 接续跨日收款，#204 处理材料计划。详见第 8 节 |
| 04 | 发布链接与客资关联验收 | CONTENT-01～04 / #164/#168/#155/#84 | 待开始 | 弱网、回执丢失、重复提交、客户触点归因 |
| 05 | 历史 PR 差异核查 | #26/#27/#106/#107 | 待开始 | 不重新合并退役 PR；形成主干覆盖与独有差异证据 |
| 06 | 我的作品功能补齐 | CONTENT-06A/B/C | 待开始 | 权威 DTO、列表、删除/恢复、任务结果入作品 |
| 07 | 内容获客成交统计 | CONTENT-07 | 待开始 | 内容、触点、客户、合同和成交关联准确且去重 |
| 08 | 设计生成退出与快速预算保留 | DESIGN-02/03/05 | 进行中 | 2026-11-26 确认保留快速预算的入口、深链、生成、保存与历史。旧平面与立面生成入口及写接口退出 |
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

1. 节点 01：FIN-03A-CI-01 已合并，Frontend CI、Backend CI 和配对验收均通过；仍待财务岗位真实业务验收。
2. 节点 02：AUTH-06 的 PR #196 已合并至 backend-main；Flyway 迁移 V20261126.1001 已在 CI 隔离环境演练，仍待生产部署、迁移执行及总部真实账号验收。
3. 节点 03 的开发、PR、配对基线和岗位验收缺口见第 8 节。
4. CONTENT-06 尚需完成作品模型、列表、删除/恢复的完整代码追踪。
5. ENG-02/03/04 等已合并代码仍需真实业务场景回归；当前未执行生产部署或真机验收。
6. 当前分支保护未启用，所有合并必须人工核验 CI 与评审，不得绕过。

---
本文件只记录执行管理状态；不代表已部署、已操作生产数据或已通过真机/三家公司验收。


## 6. 节点 00 完成与节点 01 执行记录（2026-11-26 更新）

### 6.1 节点 00 完成

- 执行日志 PR #175 已合并至 `main`，合并 SHA：`08ae5942876abe8ce5c7b794315e9cb5452fae99`。
- 基线版本 `main=6543e180013cfd803eabb535e2f9883e700f7f55` + `backend-main=27afed9127f06d2625d8b47af568edf3bccc40d5` 的配对验收 run 45631391774 成功。
- PR #175 合并后 `main=08ae594...` 的 Frontend CI run 45637055828 成功，配对验收 run 45637054667 成功。
- 后续主线持续合并其他独立 PR。当前 `main=9ce28772586fe3a6060b98265498293832f18b35` 的 Frontend CI run 45732708268 成功、配对验收 run 45732708234 成功。当前 `backend-main=196944dd5e2e49644e244e794e08683d95eaa154` 的 Backend CI run 45738883617 和 Paired release rehearsal run 45738883576 在本次记录时仍在执行；节点 01 必须以新 PR 的精确配对结果复验，不沿用旧 SHA 的结果。

### 6.2 FIN-03A 代码与权限专项复核

- 前端 `pendingRefundCorrections` 仅允许 `finance-lead` 看到“退款待重登”，且只筛选 `status=已作废`、`voidReason=更正重登`、`replacedById=null` 的记录。
- 续办时重新读取 `/franchise/finance/refunds`；页面再次确认原单仍待重登后才恢复表单。
- `correctionGuard` 检查 session、当前岗位、页面代次及页面生命周期；续办不再次调用作废接口。
- 后端沿用现有 `replacesId` 更正链及 CAS 替代权；客户退款与支出退回分别使用原收款/付款来源。
- 权限矩阵：finance 可查询/登记；finance-lead 额外可作废付款/退款；boss/owner 可查询并作废退款，不授予作废付款权限。不得将 UI 显示作为后端授权，最终以控制器 `@PreAuthorize` 和组织归属校验为准。
- 本任务不修改后端账务逻辑，不新增数据库迁移，不执行真实账务。

### 6.3 FIN-03A-CI-01｜专项回归接入正式 CI

| 字段 | 内容 |
|---|---|
| 问题 | 专项回归脚本存在，但此前未进入标准 `npm run check` 路径 |
| 修改 | 将 `node scripts/verify-finance-correction-reconcile.cjs` 加入 `check:finance-workbench` |
| 影响 | `npm run check` 与 `check:role-homes` 会间接执行 |
| 首轮分支 | `fix/finance-correction-ci-20261125` |
| 首轮 PR | #176 |
| 首轮测试 | Frontend CI run 45637730231 成功；日志明确输出“更正链、跨页恢复与对账接线全部 PASS”；配对 run 45637730230 成功 |
| 基线变化 | PR #176 创建后 main 前进 25 个提交，backend-main 也继续前进；旧 PR 虽无文件冲突，但其检查结果不作为最新主干的最终验收 |
| 当前分支 | `fix/finance-correction-ci-20261126`，从最新 main `9ce28772...` 创建，重新应用同一项测试接入及执行日志更新 |
| 数据库 | 无迁移 |
| 后端 | 无代码修改；复用现有 PostgreSQL 更正链集成测试 |
| 当前状态 | 最新基线 PR 待创建及 CI/配对复验 |

---

本文件只记录执行管理状态；不代表已部署、已操作生产数据或已通过真机/三家公司验收。


---

## 7. 节点 01/02 合并记录与节点 03 开发前审计（2026-11-26）

### 7.1 最新主线基线

| 主线 | 当前 SHA | 说明 |
|---|---|---|
| 小程序 `main` | `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` | 已包含 PR #195、#194 |
| 后端 `backend-main` | `11014a5cf936166b71134bf40f838ad93b04ce43` | 已包含 PR #196 |

PR #194（客户列表分页一致性）已合并至 main，作为节点 02 配对验收使用的前端基线；该 PR 不属于 13 个整改节点的重复开发任务。

### 7.2 节点 01｜FIN-03A-CI-01

| 字段 | 结果 |
|---|---|
| PR | [#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) |
| 目标分支 | main |
| 合并时间 | 2026-11-26 10:13:27（北京时间） |
| 合并 SHA | `41136df0162ea221ff2ba12b4f61c4da91a78c3e` |
| Frontend CI | run `45739262116` success |
| Paired frontend/backend verification | run `45739262044` success |
| Backend CI 基线 | run `45738883617` success |
| Paired release rehearsal 基线 | run `45738883576` success |
| 修改范围 | 将财务更正恢复专项脚本接入标准 `npm run check` |
| 数据库 | 无迁移 |
| 最终状态 | 代码、CI 和配对验收完成；待财务岗位真实业务验收 |

PR #176 因基线落后关闭，最终以 #195 最新 main 基线结果为准。未操作真实账务。

### 7.3 节点 02｜AUTH-06 公开门店地址

| 字段 | 结果 |
|---|---|
| PR | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196) |
| 分支 | `fix/franchise-public-address-20261126` |
| 目标分支 | backend-main |
| 合并时间 | 2026-11-26 11:07:01（北京时间） |
| 合并 SHA | `11014a5cf936166b71134bf40f838ad93b04ce43` |
| PR head SHA | `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58` |
| Backend CI | run `45745242295` success |
| Paired release rehearsal | run `45745321948` success |
| 精确配对 | main `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` + backend PR head `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58` |
| 管理台测试 | 116 项通过，管理台构建通过 |
| 数据库迁移 | `V20261126.1001__franchise_public_map_address.sql` |
| 最终状态 | 代码、专项评审、CI、配对验收完成；待生产迁移部署及总部真实账号验收 |

本次实现边界：
- 新增独立 `public_address`、`public_address_authorized`、授权人和授权时间字段；默认未授权，不从 `pf_org_profile.address` 回填。
- 只有 FRANCHISE 档案可授权；撤销授权清空公开地址及当前授权元数据。
- 地图接口只返回明确授权的公开地址；加盟商仍受同品牌过滤，总部仍受当前租户约束。
- 小程序现有地图列表、详情和搜索已支持 `publicAddress`，本轮没有重复引入地图组件。
- CI 隔离环境已执行新 Flyway 迁移；这不代表生产数据库已迁移。
- 未执行生产部署、真实门店地址录入或总部账号现场验收。

### 7.4 节点 03｜工程付款、到货与交付预审

现有 #166/#167/#173 代码已覆盖主要业务门禁，现进入最新 backend-main 的专项回归。预审发现以下幂等请求指纹缺项，先以失败测试确认，再分别修复：

| 子任务 | 预审位置 | 初步发现 | 计划验收 |
|---|---|---|---|
| ENG-IDEM-01 | `EngCollectionService.register` | 收款登记指纹未覆盖实际发生日期、凭证资产及项目归属 | 同一 key 更换日期/凭证/项目必须拒绝，原收款不得重复入账 |
| ENG-IDEM-02 | `EngMaterialService.submitPlan` | 材料计划指纹只记录节点、日期和行数，未覆盖材料名称、规格、单位及数量 | 同一 key 更换材料明细必须拒绝，不得静默返回旧计划 |
| ENG-IDEM-03 | `EngMaterialService.arrival` | 到货、补发和质量处理指纹未完整覆盖预计数量、交易号、凭证、发生日期及备注 | 原到货/补发/质量处理重试幂等；任一业务字段变化必须拒绝 |
| ENG-IDEM-04 | `EngMaterialService.arrivalCorrection` / `movement` | 到货更正原因、材料领用备注及接收人未完整纳入指纹 | 相同 key 不得静默忽略更正原因、接收人或备注变更 |
| ENG-IDEM-05 | `EngClosureService.deliver` / `settle` | 交付备注、结算凭证资产未完整纳入指纹 | 同一 key 更换交付备注或结算凭证必须拒绝 |

上述是代码审计线索，需通过测试确认具体影响；开发时必须同时处理历史幂等事件兼容，不得因扩大 payload 导致合法历史重试被误判为新请求。节点 03 仍需覆盖五期付款净额、退款后重新拦截、待补到货不计入实际到货、质量处理、交付凭据和交付后尾款规则。

---

本文件状态仅记录远程代码、CI 和配对验收证据；不代表生产部署、生产数据库迁移、真实账务操作或三家公司陪跑完成。

## 8. 节点 03 开发记录（2026-11-26）

### ENG-IDEM-01 工程收款

- [#199](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/199) 于北京时间 12:26 合入 `backend-main`，合并 SHA 为 `0c8238ee51a4955ea98be1c82c35651f55edf20f`。PR head 为 `8f6ff3b035a44d0faad351853af737cf3a62a000`。[Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45752832906) 和 [PR 配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45752895245) 均通过。
- 在 PostgreSQL 17 隔离库复现省略收款日期的请求跨日重试被拒。原三字段事件和 #199 写入的展开日期事件需分别覆盖。接续 [#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202)，同题 #203 已关闭。收款仍须由工程与财务岗位验收。
- #202 补测提交 `fa1cc1ea413721ea8f4eb9a3857aa38db24b5ef3` 复现新单显式填写日期后省略日期仍被接受。修复提交 `7494c869c00848fe49971f74c01a1605f73ef147` 区分新旧事件和日期输入。隔离库 `EngGateNetReconcileIntegrationTest` 共 10 项通过，0 跳过。前端配对目标为 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`。

### ENG-IDEM-02 材料计划

- 审计基线为小程序 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` 和后端 `0c8238ee51a4955ea98be1c82c35651f55edf20f`。原指纹只有节点、日期和行数，改变一行材料仍返回成功，合法重试又遗漏原单编号。
- [#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204) 的测试提交为 `931b17ddd6e01c9291ac052a7903784400e908e7`，实现为 `6d02bdad48db19f700e157bef1b3db39e92e5fce`。复用已有工程行锁、命令事件与材料计划编号，数据库无新增迁移。
- 两项新增测试先失败。修复后在隔离 PostgreSQL 17 执行 `EngMaterialAndPlanGuardIntegrationTest`，37 项通过、0 跳过。覆盖同键改变单行名称、规格、单位、数量或备注，以及相同输入跨批次、数量调整后的重试。
- 旧事件回读仅接受可以唯一核对的原批次。存在多份候选单、缺少备注留痕或原数量已调整时，服务提示核对原计划。请工程岗位在历史数据验收中提供此类样例。

### ENG-IDEM-03 到货、补发与质检

- [#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206) 基于后端 `0c8238ee51a4955ea98be1c82c35651f55edf20f`，配对前端目标为 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`。测试提交 `12a1064ff1399f573bbac2bc049c870910ede82f`，实现提交 `753ffe4689704b5fa48b63035e6391e85cc71cb7`。
- 原实现遗漏预计数量、流水、日期、凭证或处置说明。新事件按命令类型记录实际业务输入，供货单和工程一致后才返回原结果。旧事件通过实收行、补发批次或已保存的质检处置核对。复用现有工程行锁、命令事件及批次记录，无数据库迁移。
- 两个 PostgreSQL 用例先失败。修复后 `EngMaterialAndPlanGuardIntegrationTest` 37 项全部通过。含首收和补发原单重试、单字段变化拒绝、质检说明和凭证替换拒绝、历史事件回读。
- 旧事件缺少批次编号且同一供货单多批实收无法区分，或原应到数量已经更正时，请工程岗位核对原到货单据。

### ENG-IDEM-04 到货更正与材料流转

- [#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207) 在原单重试时核对更正原因、流转备注与接收人。旧事件回读更正记录或工程、作者对应的材料流转记录。无法区分原始记录时提示核对历史单据。
- 测试提交 `b5b992785f818e3194dd66c7ccd9c9740b5b6fae`，实现提交 `1dc892adb334783f717eaa8a7e9951100878f2ea`。两项业务差异在修复前复现。修复后工程材料集成测试 36 项通过、0 跳过。

### ENG-IDEM-05 工程交付与结算

- [#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208) 的测试覆盖交付备注、结算凭证、归档后重试及跨项目核对。复现同时提交同一结算时第二个请求收到过期版本错误。修复后在读取结算单和命令事件前先锁定工程，两个请求取得同一结算结果。新结算引用凭证时检查组织、验收状态与资产类型。
- 首轮测试提交 `cefea659ecc8678f43efb3f92a2f63899f90ece3`，实现提交 `7a1597ed5e28043436c0e78c9557c6804cd4f2d7`。独立后端分支在 PostgreSQL 17 运行两组工程集成测试，42 项通过，0 跳过。小程序主线工程凭据、联动、材料账期、节点更正四组回归共 38 项通过。

### 主线与 PR 验证

- 小程序主线 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`，后端主线 `0c8238ee51a4955ea98be1c82c35651f55edf20f`。#199 合并后的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45755260544) 和[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45755260557) 均通过。
- #202 的[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756490102)通过。后端打包下载 Spring Boot Maven 插件时 Maven Central 返回 HTTP 429，已单独重跑失败作业。[Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756470325)
- #204 的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756097825) 和[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756097846)通过。#206 的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757130825) 和[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757130824)通过。#207 的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757465179) 通过。各 PR 的小程序配对目标均为上述完整 SHA。

- 将 #202、#204、#206、#207、#208 的上述提交依序合入本地临时提交 `90aab7619d7a434357f950fed3136c9a9aef7bd1`。两组 PostgreSQL 工程集成测试共 51 项通过，0 跳过。远程后端主线仍以 #199 的合并 SHA 为准。

## 9. 节点 04 发布链接与客资关联

在上述两条主线重新执行小程序 `verify-live-manual-publication`、`verify-experience-lead-route`、`verify-live-content-p1` 和 `verify-publication-pending`，75 项全部通过。覆盖提交后回执丢失、使用原请求重试、切换账号与岗位、链接 A/B 校验、把客资关联到实际保存的发布记录。

隔离后端执行发布服务及 PostgreSQL 发布测试 18 项，CRM 与客资导入 44 项，全部通过。验证本人作品和平台账号归属、已发布记录重复回填、同手机号转待关联、重复导入与跨组织隔离。实际手机弱网、真实平台发布回执及岗位交接由业务方集中补验。

## 10. 节点 05 历史 PR 独有差异

| 历史 PR 与复核版本 | 差异对照与处理 |
|---|---|
| #26 `4bb7f772fd5b474e3bb20e0780a603b153efbf43` | 旧补丁改用手工工程专用路由。现行小程序与后端已使用合同备案路由 `projects/contract-filing`，按已签约合同或明确的独立建档模式处理。保留当前接口 |
| #27 `6d72c4061610bb9c65473b6e235f517204d06694` | 原分支校验工程与合同归属、按应付锁串行处理垫付结清。现行交接冻结测试 9 项、财务工作台 15 项通过，含同一笔垫付退款、报销、作废及结清并发的净额断言 |
| #106 `976c9c9c6904002eda84db4925cce26ae5a0ab8e` | 旧统一模拟收银台已退役。可沿用的切换身份后迟到回包保护已在现行公司额度与套餐页验证；正式 `check:wallet` 两组共 25 项通过。旧 `verify-recharge-flow` 依赖已退役的钱包桥接，执行返回“钱包功能未开通” |
| #107 `154232619cbe6dcf9455103a781147ca4261ab89` | 旧全局 MOCK 模式、模拟到账及迁移 .997～.999 继续退役。现行支付事实校验、公司充值状态及虚拟支付通知 HTTP 集成共 15 项通过。真实渠道退款与通知由节点 12 单独演练 |

## 11. 并行开发与工程专项评审（2026-11-26）

| 工程 PR | 独立评审与验证 | 主线结果 |
|---|---|---|
| [#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202) | 日期缺省、显式日期与两类历史事件。Maven 插件限流后重跑 Backend CI 成功，PR 配对通过 | 已合并 `43ff5c9e4cfef2477b75359ae5cfa047fb0da19e`。合并推送的两项检查被后续主线任务取消 |
| [#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204) | 审阅材料计划批次绑定、旧批次候选和原始数量的可还原性。后端 CI 与配对均通过 | 已合并 `bd165ba58336ab485bf7ea9f40e99db4e4eb96fb`，其推送工作流被后续主线任务取消 |
| [#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207) | 审阅原更正及原流转行、作者和接收人。[后端 CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757465179) 与[配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757465198) 均通过；在 #204 合并后的主线复验 48 项通过 | 已合并 `9a9540dea20a2f21110e549e52bec36d991091e2` |
| [#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208) | 审阅负责人、工程锁、历史交付与结算凭证及归档后重试。[后端 CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45758681207) 与[配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45758681251) 均通过 | 已合并 `337ed1a8158e58d5657cfec02d1694897c8aacb7` |
| [#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206) | 旧供货计划 10、首收约定应到 7 时，同键省略 7 曾被当作原请求。红测 `1ec8f2324b93ea65df55a41ed87ca1702fb6dad9`。修复基于原命令版本还原当时应到量，全部工程域测试 82 项通过 | 已复审，等待 head `dcb6bad42c4d18bbf2df7d86b322e2baa4a6d68e` 的远程验证 |
| [#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209) | 两个财务请求重叠时，后到请求读取不到原回执，并触发流水唯一约束。先取当前组织的工程锁，再读取幂等事件。两组 PostgreSQL 回归 49 项通过，已独立复审 | head `af5c7eed6b71bda10c762bc03293154aab5ec569`，等待远程验证 |

工程 PR 配对前端为 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`。节点 06 小程序提交 `f6625ecc23d2d317261b362b44f9b5b6070f89a3`，个人作品专项 49 项及 `npm run check` 通过。后端专项评审发现回收作品可能影响历史发布视频的播放，待修复后固定配对版本。节点 07 单独核对签约和净实收的去重口径，节点 08 单独核对设计工具预算与正式销售报价，节点 09 核对工程成本与账本。

### 节点 08 快速预算范围改正

项目负责人确认快速预算继续使用。初版后端 [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211) 与小程序 [#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) 均未合并，已转为草稿，在原 PR 内修订。验收覆盖门店及客户入口、旧链接、独立及项目预算生成、按现行规则取价和扣点、保存、重试与历史回读。AI 平面和立面生成仍按 DESIGN-02 退出。
