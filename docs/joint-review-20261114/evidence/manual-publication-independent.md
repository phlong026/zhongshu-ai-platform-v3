# CONTENT-01 手动发布登记独立验收报告

日期：2026-11-14
审查范围：仅新隔离小程序树 `zhongshu-ai-platform-v3/.worktrees/joint-review-20261114` 与 split B 后端树 `众墅之家AI赋能平台/.worktrees/joint-review-20261114/平台程序`。未审、未运行旧 root/旧管理后台。
审查方式：只读代码审查 + 小程序行为测试 + 后端唯一 Maven 通道结果复核；未修改业务文件。

## 结论

**APPROVE（代码候选通过）**。

- 未关闭问题：CRITICAL 0 / HIGH 0 / MEDIUM 0 / LOW 0。
- Stage 1 业务契约通过：首发只做人工发布登记，不提供定时/自动发布；历史 `scheduled` 记录保持历史态，仅在本人显式登记链接时执行 `scheduled -> verify -> published`。
- Stage 2 代码、安全与回归通过：组织隔离、本人写权限、本人作品归属、幂等重试、切岗迟到保护、真实 records/detail 回读及失败重试均有实现与测试证据。
- 本结论是“本地代码候选通过”，不等同部署、体验版上传、生产发布或真实账号 UAT。

## 规格符合性证据

1. **无定时入口、旧草稿不恢复定时**
   - `miniprogram/pages/studio/index.js:38-47` 仅恢复人工账号、平台、标题、文案等字段，忽略旧 `selectedAccount/scheduled`。
   - `miniprogram/pages/studio/index.wxml:29-35` 只显示平台、实际账号和“创建发布记录”，无定时或自动发布入口。
   - `ContentPublicationService.java:43-58,229-237` 在幂等查询、作品查询及写入前拒绝非空 `scheduledAt`。

2. **真实账号元数据与平台契约**
   - `index.wxml:31-35` 明示账号“仅用于记录，不代表账号授权或自动发布”。
   - `platform-content.js:73-81` 校验账号 1..64 字符、平台仅 `douyin|channels`。
   - `ContentPublicationService.java:210-227` 后端同样校验账号长度和平台白名单，避免绕过小程序写入未知平台。

3. **人工登记闭环与真实回读**
   - `index.js:121-161` 映射并展示真实 `accountId/publishText/tags/authorUserId/createTime`，records/detail 加载失败有显式错误和重试，切岗迟到结果不回写。
   - `index.js:413-445` 创建请求不发送 `scheduledAt`；相同 scope+payload 失败重试复用同一幂等键，payload/scope 变化换键，迟到成功不导航。
   - `index.js:447-498` receipt 从后端记录回读并执行人工 `verify -> link`；本人以外只读。
   - `ContentPublicationController.java:41-85,96-113` 写入口均要求 WRITE 权限，返回新增真实字段。

4. **组织隔离与对象权限**
   - `ContentPublicationService.java:61-64` 创建前要求作品属于当前组织且作者就是当前用户，关闭同组织同事作品冒名登记。
   - `ContentPublicationService.java:143-157` 列表按 `companyId` 隔离；verify/link/fail 以 `companyId + userId + publicationId` 校验本人写权限。
   - `ContentPublicationPgIntegrationTest.java:113-128,152-165` 覆盖同组织同事、跨组织作品不可创建，以及同事/外组织不能推进历史记录。

5. **历史 scheduled 保留且不自动发布**
   - 生产内容域唯一 `@Scheduled` 是内容任务扫描 `ContentTaskJob`，没有发布记录调度器。
   - `ContentPublicationService.java:127-130,159-165` 仅用户显式 verify 时允许历史 `scheduled` 转换，并清空受表约束的 `scheduled_at`。
   - `ContentPublicationService.java:184-200` 将原计划时间保存在 `versions.originalScheduledAt`，继续保留冻结签名。
   - `ContentPublicationPgIntegrationTest.java:131-150` 以真实 PostgreSQL 约束验证人工转换、链接登记、原计划时间追溯与签名不变。

## 审查中发现并已关闭的问题

- **HIGH（已关闭）**：创建发布记录过去只校验作品属于组织，未校验作者，可能由同组织同事冒名登记。现由 `ContentPublicationService.java:61-64` 拒绝，并有 PostgreSQL 同组织/跨组织回归。
- **MEDIUM（已关闭）**：创建响应的 `createTime` 曾依赖数据库默认值但直接返回内存对象，首个响应为空。现于 `ContentPublicationService.java:67-95` 同时写入对象和数据库，HTTP 契约测试验证非空。
- **MEDIUM（已关闭）**：小程序使用 `channels`，后端测试及宽松校验曾允许 `wechat_channels`，回读会被前端误显示为抖音。现服务端白名单与测试统一为 `douyin|channels`，非法平台在查询/写入前拒绝。
- **MEDIUM（已关闭）**：历史 `scheduled` 转换若不清空 `scheduled_at` 会违反现有 CHECK；直接清空又丢失历史时间。现清空主列并把原时间写入 `versions.originalScheduledAt`，真实 PG 测试通过。
- **MEDIUM（已关闭）**：详情页曾给已发布记录显示“修改链接”、给 failed 记录进入不可办理回执。现 `index.js:501-507` 与 `index.wxml:47-55` 仅允许 `publishing|verify|scheduled` 且本人进入回执；已发布仅复制、失败仅查看原因。
- **LOW（已关闭）**：发布页曾保留空白“更多设置”入口，失败文案曾误称账号授权失效。入口已删除，账号明确是非授权元数据，并有静态门禁。

## 验证证据

### 小程序

- 独立复跑：`node scripts/verify-studio-manual-publication.cjs` — PASS。
- 独立复跑：`node --check miniprogram/pages/studio/index.js` — PASS。
- 独立复跑：`git diff --check` — PASS。
- 实施通道最终：`npm run check` — exit 0；日志 `/tmp/zs-manual-publication-mini-check.log`。
- 行为门禁覆盖：人工账号/平台、无定时、旧草稿忽略 scheduled、同 payload 重试同键、账号/会话/岗位变化换键、迟到不导航、历史 scheduled 人工 verify/link、records/detail 真实字段、失败重试、他人只读、published/failed 不误入回执。

### 后端

- 专项命令：`./mvnw ... -pl domains/franchise/franchise-content -am -Dtest=ContentPublicationServiceTest,ContentPublicationHttpContractTest,ContentPublicationPgIntegrationTest test`
  - 12 tests，0 failure，0 error，0 skipped；29 个 reactor 模块全部 SUCCESS。
  - 日志：`/tmp/zs-manual-publication-targeted-green.log`。
- 内容域完整命令：`./mvnw ... -pl domains/franchise/franchise-content -am test`
  - 64 tests，0 failure，0 error，0 skipped；29 个 reactor 模块全部 SUCCESS。
  - 日志：`/tmp/zs-manual-publication-content-final.log`。
- 独立复核：后端 `git diff --check` — PASS。
- 环境未暴露 `lsp_diagnostics`/`ast_grep_search` 工具；以 Maven 全编译与测试、Node 语法检查及定向 `rg` 安全模式扫描替代。未发现硬编码密钥、空 catch 或生产调试输出。

## 上线前外部门禁（不改变本轮代码 APPROVE）

以下尚未由本地代码测试证明，发布前仍为 No-Go 门禁：

1. split B 后端及本次小程序包尚未在目标生产/预发环境部署、上传或发布。
2. 尚未用真实微信设备、真实岗位账号做端到端 UAT：本人创建、同组织同事只读、跨组织不可见、切岗途中迟到响应不导航。
3. 尚未用真实抖音/视频号人工发帖与真实分享链接完成 `create -> verify -> link -> records/detail` 回读。
4. 尚未执行生产数据备份与回滚演练；本次无 schema 变更，但上线前仍应确认历史 `scheduled` 数据量、状态分布及回滚方案。
5. 本轮未做首发 100 人同时发起 AI 生成的容量压测；CONTENT-01 人工登记代码通过不代表整个平台容量门禁通过。
