# Java/Web 分支处置记录

审查日期：2026-11-15。功能基线：`95b382b0`（独立 B 系统/统一登录）。集成分支：`codex/integrate-backend-branches-20261115`。

## 主线边界

- 新设 `backend-main` 沿用 Java/Web 独立历史；本候选包含 local `main=0158ffec` 且可正常 fast-forward。
- 本地缓存 `origin/main=a28a1a50`，用户提供的远程实时快照为 `0f60217b`；二者都属小程序历史，与 Java/Web 候选无共同祖先，禁止 `--allow-unrelated-histories`、禁止覆盖远程 `main`。
- 旧 AI 个人积分/公司积分池链不是独立 B 首发范围，不从旧融合分支整链恢复。

## 本地分支逐项处置

| 分支 | 审查 SHA | 处置 | 理由/证据 |
| --- | --- | --- | --- |
| `main` | `0158ffec` | 已覆盖 | `git merge-base --is-ancestor 0158ffec 95b382b0` 成功；为 `backend-main` 祖先锚点。 |
| `feat/franchise-account-login-platform-20261115` | `95b382b0` | 基线 | 独立 B/统一登录最新候选。 |
| `codex/fix-six-role-flow-20261115` | `877e8cc8` | 已覆盖 | 候选完整包含，独有提交 0。 |
| `feat/b-independent-20261114` | `46cc52ea` | 已覆盖 | 候选完整包含，独有提交 0。 |
| `feat/platform-split-docker-20261110` | `60e70cfb` | 已覆盖 | 候选完整包含，绩效导出以此为父链另行最小整合。 |
| `fix/joint-release-split-20261114` | `4f6cd0d3` | 已覆盖 | 人工发布/安全边界提交已在候选祖先链。 |
| `feat/m2-franchise-platform` | `01e4245c` | 已覆盖 | 独有提交 0。 |
| `feat/m5-financedesk-20261103` | `4e0c5b5d` | 已覆盖 | 独有提交 0。 |
| `feat/stage0-common-base-20261101` | `44861f59` | 已覆盖 | 独有提交 0。 |
| `feat/stage1_5-content-20261102` | `426c7ad5` | 已覆盖 | 独有提交 0。 |
| `feat/stage2-engineering-20261102` | `6b9e6b31` | 已覆盖 | 独有提交 0。 |
| `feat/stage3-performance-20261102` | `7be675e4` | 已覆盖 | 独有提交 0。 |
| `feat/stage4-mine-20261102` | `0c1aee77` | 已覆盖 | 独有提交 0。 |
| `fix/review-r13-20261103` | `b9421162` | 已覆盖 | 独有提交 0。 |
| `codex/local-sync-backend-performance-export-20261115` | `3ea1ccfd` | 最小整合 | 12 文件绩效提成导出以单提交整合为 `3d0a7d72`；`fr-perf-export.test.js` 先复现 Prettier 失败再格式修复。 |
| `codex/b-preflight-20261115` | `841f0eb4` | 最小整合 | `1e5ea653` 预检脚本与基线 blob 相同；仅整合更新的历史预检报告为 `5b8cab52`，不将历史证据写成当前验收。 |
| `codex/local-sync-backend-preflight-docs-20261115` | `c448fb9e` | 归档 | 同一预检资料的较旧保存，日志较少且状态/独立审查口径较弱，不覆盖 `841f0eb4`。 |
| `codex/local-sync-backend-readme-20261115` | `68978b0a` | 归档 | 挂在旧 org 链且含 A/48080 说明；当前 README 已明确 B/48081，禁止回退。 |
| `codex/local-sync-backend-stash-20261115` | `5ecbac7b` | 异源，路由 mini | 无共同祖先的 6 个 miniprogram 文件；工程更正/取消防阻塞已由 mini `2779d915` 等更成熟实现覆盖，不进 `backend-main`。 |
| `feat/backend-miniapp-parity` | `c156c0d1` | 归档，仅摘有效缺口 | 71 个独有提交大部分是旧融合后端/旧 AI 积分链。不整链合并；只从 `62ad61a1` 复现并最小补回 scheme stage 读侧与 delivery requirement 幂等指纹。`e32a91c5` 的 channelAllowed 当前 B 已存在。旧 A 的 SMS/日志/生产模板差异已经 B 路径逐项判定为不适用，见下节。 |
| `feat/org-point-pool-20261113` / `fix/joint-release-audit-20261114` | `f77fad1c` | 归档 | 65 个独有提交是公司积分池/旧融合链，与独立 B 系统不迁移旧积分的已定边界冲突。 |
| `feat/web-identity-completion-20261109` | `cdbe4751` | 归档 | 14 个独有提交为旧账号/积分设计资料，当前 B 统一账号实现已取代。 |
| `phlong026/crinoid` | `94097a24` | 异源 | 无共同祖先，不盲合。 |

## 同历史远程独有分支

| 分支 | 审查 SHA | 处置 | 理由/证据 |
| --- | --- | --- | --- |
| `origin/cursor/miniapp-backend-gap-closure-01f4` | `0ac2c827` | 最小摘取后归档 | 3 个独有提交中 `e32a91c5` 已覆盖，`0ac2c827` 是旧路径报告；`62ad61a1` 的两个实质缺口经当前基线复现后最小移植，不覆盖新版报价面积/委托依据校验。 |
| `origin/cursor/full-chain-parity-audit-c2a1` | `8c19ab49` | 最小摘取后归档 | 包含上述 3 提交及合并/旧审计文档；无额外当前 B 必需代码。 |

其余同历史 `origin/*` 分支与同名本地分支 SHA 相同或已被 `95b382b0` 完整包含，处置同上表。异源远程（含 `origin/main`、`origin/master`及小程序/production 旧链）全部保留，不合并不删除。

## 最小移植判定

- `CrmRespVos.Scheme` 基线未返回 `CustomerSchemeDO.stage`，导致小程序重进客户详情后无法恢复 `formal`。按现有实体真源返回，历史 null/空值按 `reference`。
- `SaleQuoteService` 当前内联指纹已包含 `customerId`、`schemeId`、明细、总额、宽/深/层数、面积、价目版本与委托地址；`SaleQuoteBudgetIntegrationTest` 覆盖同参重放、尺寸/价目版本变化拒绝及实际面积冻结。旧提交的 `createFingerprint` 功能已被当前更完整参数等价覆盖，不移植旧方法，避免回退新版尺寸校验/客户锁/预算方案逻辑。
- `SaleDeliveryService` 基线指纹只含 `operation+contractId`，同一幂等键更换 `requirementId` 会错误重放旧交付；新指纹补入 `requirementId`。`FranchiseCommands` 仅在调用方显式提供旧请求串时允许旧 hash 回放，`SaleDeliveryService` 回放后再用已存聚合校验 `contractId+requirementId`：同业务可回放，同旧键换需求仍拒绝，不放宽其他命令的幂等规则。

## 旧 A 配置/SMS/日志/积分链判定

- `3a8661c7`/`0a1fb44d`/`c156c0d1` 修改的是旧 A `deploy/env.prod.template`。独立 B 不使用该模板；B 的 `deploy/platform/platform-identity-content.properties.example` 已显式列出 SMS provider、sdk-app-id、sign-name、template-id、secret-id、secret-key 和 region，`PlatformEnvironmentPolicy` 在 real 通道下对全字段拒绝缺失启动。旧 A 的已审核模板 ID/密钥不得复用到 B。
- B `yudao-platform-server/logback-spring.xml` 只输出 console，`deploy/platform/compose*.yaml` 设置容器日志轮转；旧 A 的 `LOG_FILE` 路径不适用，恢复会破坏 A/B 文件系统隔离。
- 旧 org 65 提交的账号映射、存量个人积分迁移、旧 prompt/计费路由与激活码退役属于融合 A 方案。当前 B 在 `platform/platform-wallet/orgpoint` 独立实现公司池，已定两系统独立且不迁移旧个人积分，因此无需移植。

## 证据位置

- 原始分支矩阵：`/tmp/zhongshu-branch-review-matrix-20261115.json`、`/tmp/zhongshu-backend-integration-20261115/matrix.tsv`
- 命令、测试、构建、格式与安全日志：`/tmp/zhongshu-backend-integration-20261115/`
- 历史远程保存回执：本次只作分支来源证据，不替代当前候选验证。
