# 八步流程成为客户跟进的唯一事实（2026-11-26）

背景和小程序侧的改动见小程序仓库 `docs/客户跟进八步唯一口径_20261126.md`。本文件记录后端和管理台的改动、口径、兼容和验证结果。

- 小程序侧的新版本依赖本次新增的字段和接口，**后端先上线，小程序再发布**。
- 本文只记录代码改动和自动化测试结果。没有执行数据库迁移，没有部署。

## 口径

列表分栏、首页汇总、待办、首页指标读的是同一份客户表投影（`fr_customer.last_follow_at`、`next_visit_date`、`status`），这份投影由八步服务写入。

| 名称 | 定义 |
| --- | --- |
| 今日待跟进 | 未流失，且（`last_follow_at` 为空，或 `next_visit_date` 不晚于今天） |
| 预约跟进 | 未流失，且 `next_visit_date` 晚于今天 |
| 已到店 | 未流失，且第 2 步“店面讲解”有记录，或升级前登记为“实际接待”的普通跟进 |
| 跟进中 | 跟进过，且未流失、未暂缓 |
| 流失 | `reasonCode` 必填（`PRICE`、`COMPETITOR`、`PLAN`、`DEFERRED`、`UNREACHABLE`、`OTHER`）；`OTHER` 必须自填说明，其余不填时用默认文案；`next_visit_date` 清空 |
| 暂缓 | 说明必填，`nextFollowOn` 必须晚于今天；到期自动回到今日待跟进 |
| 恢复 | 下次跟进日期落到今天 |
| 超期未跟进 | 新客登记满 2 天仍没有跟进记录；或最近一次跟进超过 15 天且没约下次（约了日期的由到期待办提醒） |

## 改动

**数据**（`V20261126.1104__customer_followup_single_source.sql`，撤销脚本在 `db/migration-undo/franchise/`）

- `fr_customer_workflow_step`、`fr_customer_workflow_event` 增加 `next_follow_on`。
- `fr_customer_workflow` 增加 `status_reason_code`、`status_reason`、`reconsulted_at`。
- `fr_customer_workflow_event.action` 的约束放开 `CONSULT`。原约束是建表时内联的，脚本按名字查找后替换，找不到就报错停止。
- 新增索引 `idx_fr_customer_next_visit (company_id, next_visit_date)`。
- 不搬迁升级前的跟进表 `fr_customer_follow`：时间线读取时合并，清理批再删除。
- 撤销脚本在已有 `CONSULT` 事件时拒绝执行，避免丢记录。

**八步服务**（`franchise-crm` 的 `workflow` 包）

- 保存步骤可带 `nextFollowOn`，不能早于今天；写入步骤、事件，并同步到客户的 `next_visit_date`。不带日期的保存不改约定。
- 升级前小程序保存第 1 步的“下次联系”（`nextContactOn`）只留在步骤里，不改客户的下次跟进日期，和客户列表只认 `next_visit_date` 的规则一致（小程序 #287）。
- `status`：流失、暂缓、恢复的规则见上表。流程版本、幂等键、审计记录与其他八步命令一致。
- `timeline`：步骤记录、补充记录、状态变更、再次咨询、升级前的普通跟进，按时间倒序分页。
- `recordConsult`：客资关联到老客户时追加一条 `CONSULT` 事件并把流程版本加一。未流失的客户，下次跟进日期没约定或晚于今天的提到今天；已流失的只记 `reconsulted_at`，不自动恢复。
- 补充记录、跳过带看、重开步骤会更新客户的最近跟进时间，不改下次跟进日期。

**客户列表与汇总**（`CustomerMapper`、`CustomerService`）

- `FollowFilter` 三个分栏都排除已流失；“已到店”增加第 2 步有记录的条件。
- 新增 `selectStale`、`selectReconsulted`、`followSummary`（和分栏同口径的计数）。
- `listAwaitingFirstFollow` 显式排除已流失。

**客资**（`LeadService`）：登记时自动关联老客户、或负责人手工关联，都会调用 `recordConsult`。

**待办**（`FranchiseTodoProvider`）

- “待首次跟进”“约定跟进已到期”点击进八步页。
- 新增“流失客户再次咨询，待确认是否恢复”。
- 业务负责人新增“超期未跟进”。

**首页指标、团队待跟进、绩效**（`franchise-performance`）

- “待跟进”“跟进商机”排除已流失；“实际接待”增加第 2 步有记录的客户。
- 团队待跟进名单排除已流失。
- 绩效“有效客户”的跟进记录合并升级前的普通跟进、八步事件（不含再次咨询）和补充记录。

**消息**（`BusinessMessageFacts`）

- 新规则：客资关联到老客户通知原负责人（“老客户有新的咨询登记”）；客资登记通知负责人（“客户咨询已登记”）；别人完成了你负责的客户的步骤时通知负责人（“客户跟进有新进展”）。经办人自己不收。
- 这三条规则上线前（2026-11-26 之前）的历史事件只补检查点，不补发。

**管理台**（`admin-ui`）：客资表格“状态”改为“跟进阶段”（已流失、暂缓、当前步骤和进度），“下次回访”改名“下次跟进”。

**接口**

| 接口 | 变化 |
| --- | --- |
| `GET /franchise/customers/follow-summary` | 新增。当前可见客户的跟进汇总 |
| `GET /franchise/customers/{id}/workflow/timeline` | 新增。合并后的跟进时间线 |
| `POST /franchise/customers/{id}/workflow/status` | 入参增加 `reasonCode`、`nextFollowOn`；流失、暂缓的校验见上表 |
| `PUT /franchise/customers/{id}/workflow/steps/{step}` | 入参增加 `nextFollowOn` |
| `GET /franchise/customers/{id}/workflow`、`/workflow-summaries`、`/workflow/records`、客户列表行 | 返回增加 `nextFollowOn`、`statusReasonCode`、`statusReason`、`reconsulted`、`canChangeStatus`（按接口不同） |

## 兼容

- 升级前的小程序仍调用 `POST /franchise/customers/{id}/follows`，接口原样保留一个版本。它照常更新客户的最近跟进时间和下次跟进日期，并行期间老版本登记的跟进在新口径里同样生效。
- 已发布的小程序不调用 `workflow/status`（只显示状态），所以暂缓改为必填再联系日期不影响已发布版本。
- 完成本步必填下次跟进日期目前只由小程序把关：已发布的八步页不带这个字段，服务端现在只校验和保存。旧版本退出后在清理批改为服务端必填。

## 验证

Testcontainers PostgreSQL 17，全部通过：

| 测试 | 数量 | 覆盖 |
| --- | --- | --- |
| `CustomerWorkflowIntegrationTest` | 23 | 下次跟进日期、流失与暂缓的校验、时间线合并、汇总口径、“已到店”的可见范围 |
| `CrmIntegrationTest` | 27 | 客户、跟进、转交、列表分栏 |
| `FranchiseTodoProviderTest` | 9 | 待办文案和跳转、超期未跟进、流失再次咨询 |
| `FranchiseBusinessMessagesTest` | 22 | 三条新规则、经办人不收、历史事件不补发 |
| `PerfFinanceWorkbenchIntegrationTest`、`PerfMetricsTest` | 13 + 5 | 首页指标、团队名单、有效客户 |
| `OrgPointHeadquartersIntegrationTest` | 17 | 总部读取客户跟进 |
| `CustomerWorkflowAssetPgIntegrationTest`、`ExternalHandoverPgIntegrationTest` | 11 + 32 | 八步步骤的附件与离职交接（版本号读取） |
| `SaleQuoteBudgetIntegrationTest`、`DepartureHandover*` | 14 + 7 + 10 + 14 | 相邻链路回归 |

改动前就失败、与本次无关：`LegacyPlatformCrmCustomerInterceptorTest`（12 个里 8 个失败）、`LegacyPlatformLeadInvalidateInterceptorTest`（30 个里 22 个失败）。两组的固定迁移列表只执行到 11 月 2 日，缺地址列；在未改动的 `backend-main@adec9e6a` 上结果相同。

没有做的：在真实库上执行迁移、部署、管理台人工验收。

## 清理批（旧版本退出后）

1. 删除 `POST /franchise/customers/{id}/follows` 和小程序里的本地样例跟进数据。
2. “完成本步”改为服务端必填下次跟进日期。
3. 删除 `fr_customer_follow` 表和读取时的合并逻辑。
