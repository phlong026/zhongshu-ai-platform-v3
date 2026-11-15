# Java/Web 集成阶段报告

更新时间：2026-11-15 18:15 CST。基线 `95b382b0`，round 1 头 `83e00cfd`；round 2 以本文档所在逻辑提交为准。

## 已完成

- 绩效导出 12 文件整合为 `3d0a7d72`；预检较新历史报告整合为 `5b8cab52`。
- `fr-perf-export.test.js` Prettier 失败已先复现后修复。前端专项 50/50 通过，Prettier/ESLint/Stylelint/Vue TypeScript 都返回 0。
- scheme stage 回读、delivery requirement 幂等指纹和历史 hash 兼容已实现；最终代码版本专项 7/7 通过。
- 本地与同历史远程分支均已分类；异源 mini stash/远程 main 不合并。

## 当前验证状态

- 导出 Java 首轮：17 个 PostgreSQL 集成用例因 Surefire 继承系统 SOCKS 代理访问 localhost 而失败；非 PG 导出用例已通过。
- 导出 Java 第三次环境重试：Testcontainers 和 PostgreSQL 已在 `127.0.0.1` 启动，但 Surefire 子 JVM 卡在 Byte Buddy 动态 attach，日志 6 分钟无增长、CPU 0；已只终止本任务 PID。
- 最终有界运行仅对当前 JVM 预加载 Byte Buddy agent 并绕过 localhost 代理：导出 36/36 通过，其中 PostgreSQL 集成 17 项；报价 PostgreSQL 集成 6/6 通过。
- Java 17 `yudao-platform-server -am -DskipTests package` 39 个 reactor 模块全部成功，总时长 42.045 秒。
- round 2 签名顺序修复后，完整相关集 61/61 通过（其中绩效导出 46，其他 round 1 回归 15）；最新 Java 17 平台构建再次通过。

## 交付前剩余项

1. 完成 round 2 中文 Lore 逻辑提交和提交后 clean/祖先关系证据。
2. 由主代理安排限定独立复审；本分支不 push、不合并。

## 独立初审 round 1

- 初审结论 `REQUEST_CHANGES`（P1=2、P2=3），冻结点 `91136bb5`。
- 绩效导出 N+1、审计时序、未知 stage、Snowflake ID 精度和 delivery 旧 hash PG 覆盖均已按初审要求整改。
- 新鲜专项：`PerfCommissionExport*` 43/43；`FranchiseCommandsTest + SchemeViewTest + SaleDelivery* + SaleQuoteBudgetIntegrationTest` 合计 15/15。
- 查询计数与审计调用顺序原始日志在 `/tmp/zhongshu-backend-integration-20261115/review-fix-round1-*`。

## 独立复审 round 2

- round 1 五项整改已获 `APPROVE`；复审新发现批量导出按收款 ID 排序签名，而旧 `stageFacts` 可按数据库物理顺序保存，会产生假“待核对”。
- 新签名生成已固定按收款 ID 排序；旧工程签名仅对 `c:<id>:<amount>:<refunded>` 片段顺序做等价比较，`required`/金额/退款仍严格，统一合同与未知格式仍按原文比较。
- 有效红测为真实 PostgreSQL 倒序 ID 插入：修复前导出待核对从期望 0 误报为 1；修复后导出与服务行视图均为 0，反序历史签名同样兼容。

## 远程接续边界

- Java/Web 的 canonical 远程目标是同一 `origin` 上的 `backend-main`；远程 `main` 为小程序独立历史，不与后端合并。
- 后续单分支克隆：`git clone --branch backend-main --single-branch https://github.com/phlong026/zhongshu-ai-platform-v3.git <目录>`。
