# 小程序分支逐项处置（2026-11-15）

## 审查口径

- 集成基线：`cec84cc0f1b1f71dd46bd6d38054cf101eb14891`；目标主线：`main@0f60217b915475ea2616577a9945b7fa953e9b9b`。后者是前者祖先，可快进，不用倒灌旧快照。
- 只处理小程序历史。Java/Web 后端以独立 root 和 `backend-main` 交付，不进入本候选。
- “独有提交”不直接等于“缺功能”。每项均按当前源码、补丁等价性或专项行为测试判定；旧验收文档只按日期归档，不替代当前结论。
- 所有业务用户按加盟商员工处理；未恢复个人钱包、旧 AI 设计积分、旧本地演示数据、自动/定时内容发布。

## 有业务价值且已最小集成

| 来源 | 处置 | 当前候选证据 |
| --- | --- | --- |
| `codex/fix-role-workflows-20261115@ac848714` | 集成应付有效已付/支出退回投影；付款入口只展示真实未付余额。没有整支覆盖当前账号和会话守卫。 | `platform-finance.payableProgress`；`verify-finance-payable-progress.cjs` 7项通过；`verify-finance-project-readonly.cjs` 使用真实投影。 |
| `codex/local-sync-mini-role-workflows-20261115@51ce7b7b` | 父提交 `ac848714` 如上集成；快照35文件与候选同 blob，13份只作历史归档；`package.json` 只挂入财务专项检查。 | `docs/full-business-audit-20261114/runs/AUDIT-20261115-R03/archive-role-workflows-snapshot/`；未覆盖账号源码。 |
| `cursor/fix-voucher-post-and-scheme-stage-1aa4@ca217bb1` 中 `df01fccd` | 集成正式方案 `stage=formal` 回读和刷新保留；未恢复旧模拟数据盘点 `ca217bb1`，上传 `bfe06554` 由新版 BAsset 覆盖。 | `platform-crm.detailView` 与 workflow 刷新；`verify-live-workflow-extra.cjs` 通过。后端仍须由独立候选提供 `reference/formal` 真源字段。 |
| `fix/joint-review-mini-isolated-20261114@2015f8c5` 中 `14e1dde3` | 集成银行到账确认稳定幂等键，并补强切岗后迟到 `then/catch/finally` 守卫。 | `verify-finance-bank-confirm-idem.cjs`：失败重试同键，成功/收款单/组织/岗位变化换键，迟到 finally 不清新现场。 |
| `codex/local-sync-mini-release-notes-20261115@f3d072dd` | 两份已脱敏发布范围文档作为 2026-11-14 历史归档；不写成当前上线结论。 | `docs/evidence/release-20261115/archive-20261114/`。 |

## 独有提交已由新版能力覆盖，不合旧实现

| 分支 / SHA | 最终判定与证据 |
| --- | --- |
| `codex/local-sync-mini-audit-extra-20261115@67c6068f` | 不移植旧 mock。当前两份测试是更强版本：财务专项4项通过；workflow mock已包含 `sessionGeneration` 并通过。日志：`/tmp/zhongshu-mini-integration-20261115/audit-finance-current.log`、`audit-workflow-current.log`。 |
| `codex/local-sync-mini-wechat-readiness-20261115@9bd66a7c` | 旧文档仍写“待修复/待确认”，当前同路径已有修复执行与成功记录；旧文件会回退现场结论，故保留原分支、不覆盖。 |
| `feat/platform-split-wallet-20261111@41e5cb42` | 不恢复个人充值、积分卡或 `design-intent.js`。当前设计意图通过服务端冻结项目、参考图 ID 指纹、稳定两阶段幂等键和项目列表恢复持久化；`verify-reference-project-contract` 7项及 `verify-design-pages` 通过，日志 `design-current-coverage.log`。旧独立 AI 设计系统与赋能平台公司积分继续隔离。 |
| `fix/joint-review-mini-isolated-20261114@2015f8c5` 中 `ce487a97` | 上传与异步守卫由新版协议覆盖：财务/工程走 `BAsset.putTicketBytes`、岗位头由当前 HTTP 层提供，业务/设计/视频均有 session generation/guard，迟到响应丢弃。相关13项通过，日志 `upload-async-current-coverage.log`。不恢复 raw COS helperUser 代传。 |
| 同分支 `c0b02d9c`、`2015f8c5` | 当前 `3c75ce83` 已接入手动发布登记，稳定重试且无排期/自动发布；16项通过，日志 `manual-publication-current-coverage.log`。旧联合审查证据只保留原分支。 |
| 同分支 `4da9c15c`、`383608ed` | 旧团队幂等测试与联合审查归档不覆盖当前更强集成测试和当前日期报告。没有业务代码缺口。 |
| `feat/miniapp-layout-unification-20261030@965ee3b2` | 50个旧布局路径均已被新版品牌/岗位布局继续修改。当前任务、提成、旅程和全局按钮已覆盖 `min-width:0`、`minmax(0,1fr)`、输入框 `box-sizing` 与长文本换行；统一回归3项通过，日志 `branch-integration-current.log`。不套回旧50文件快照。 |
| `fix/owner-layout-reuse-20261030@e7cb919b` 中 `d173ff93` | 当前老板首页直接进入在线客户和工程 API 路由；客户页识别 boss/owner 并分页请求，工程页识别只读视图与在线项目请求。`verify-api-role-pages`、`verify-live-owner-p2` 及统一回归通过。旧本地/demo入口不恢复。 |
| 同分支 `b9f140c6` | 长文本布局已由当前任务/提成/旅程样式覆盖，统一回归锁定；旧样式整体合并会回退新版布局。其余两个提交仅旧发布记录，不当当前验收。 |
| `codex/local-sync-backend-stash-20261115@5ecbac7b` | 名称虽含 backend，实际是旧 mini stash。正式 `2779d915` 已包含材料调整/到货/现场/节点更正、撤回和取消材料不阻塞，并多了回归脚本；后续 `06d5994b` 已删除旧 build model。恢复 stash 会复活退役架构，故不移植。 |

## 补丁等价，无需重复移植

| 分支 / SHA | 处置 |
| --- | --- |
| `feat/fr-brief-payment-void-20261113@262d32fc` | 3个独有 SHA 均由 `git cherry` 判定补丁等价；当前候选已含相同行为。 |
| `feat/messages-api-integration-20261101@da5e8821` | 5个独有 SHA 均由 `git cherry` 判定补丁等价；当前候选已含相同行为。 |

## 已被基线完整包含

以下本地或同历史远程 tip 对候选独有提交数均为0，禁止重复合并：

- `codex/fix-experience-catalog-20261115@98e7d2b2`
- `feat/franchise-account-login-mini-20261115@cec84cc0`
- `feat/full-business-flow-audit-20261114@a06b298b`
- `feat/release-readiness-tasks-20261113@e2962cb0`（远程别名 `b8b37f89`）
- `fix/joint-release-audit-20261114@b8b37f89`
- `fix/live-integration-20261114@78c2955d`（远程 tip `fa7a85c8` 也被包含）
- `main@0f60217b`
- `feat/account-api-integration-20261102@3c0d7a09`
- `feat/boss-workspace-d-20261029@06469d2e`
- `feat/content-api-integration-20261102@eb14acb3`
- `feat/engineering-api-integration-20261102@209a50a3`
- `feat/flow-consistency-audit-20261029@3bebe3ba`
- `feat/flow-consistency-remediation-20261029@1b0c2e9b`
- `feat/full-flow-acceptance-20261023@0fd622e0`
- `feat/login-dual-channel-20261031@b3a2e63c`
- `feat/mgmt-batch-bcd-20261029@0efefd87`
- `feat/performance-api-integration-20261103@c7d20edb`
- `feat/production-mock-removal-20261107@3c7fdcab`
- `feat/profile-api-integration-20261101@2b02b426`
- `feat/six-role-project-closure-20261030@6752ca90`
- `feature/process-refactor-20261020@488fac10`
- `fix/engineering-record-corrections@2779d915`
- `fix/profile-navigation-20261109@a550728e`
- `master@c23322b1`

## 独立历史，不适用于小程序 main

实时远程中以 `95b382b0` 为候选的 Java/Web 后端分支（含 `backend-main` 目标链、`feat/backend-miniapp-parity`、`feat/org-point-pool`、`cursor/full-chain-parity-audit`、`cursor/miniapp-backend-gap-closure` 等）与本候选 root 不同，由后端候选独立审查；不得合入小程序 `main`。本次唯一跨端契约是 `Scheme.stage`，代码仍分别提交和审查。

## 安全清理与非交付项

- 删除已跟踪的体验版完整二维码 `docs/evidence/b-independent-20261114/T26/B-experience-1.0.20261115.2.png`；相对文档改为“二维码留在 Git 之外并从微信后台实时生成”。未重写旧历史，也未重新上传体验版。
- 未推送、未合并 `main`、未调用真实生产 API、未上传体验版、未做真机/真实岗位 UAT。依赖漏洞修复由主代理安排的独立任务处理，不在本业务提交修改依赖字段或锁文件。
