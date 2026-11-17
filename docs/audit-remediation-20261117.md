# 整改 01–06：后端实施与验证

基线：`backend-main 61f60a92`；配套前端基线 `main 27e6b2fb`。

## 工作区核对

修改前已检查全部已登记 Git 工作区及相关文件的内容摘要。人物库旧工作区的修改已包含于当前基线；后端主工作区的退款文件另有“退回资金归属”未提交改动，未混入本次提交。使用独立 `codex/audit-remediation-backend-20261117` 分支实施，提交前再次核对重叠文件。

## 改动

- 01：退款和支出退回的交易号转移同时比较公司、交易号、原归属类型及原记录 ID。条件更新失败或并发占号返回明确业务错误；原退款替代权、新退款及幂等记录同事务回滚。
- 03：正式人物增加版本；个人草稿保存其来源版本。发布按正式人物 ID、公司、版本和未删除条件更新。失败保留草稿。旧草稿没有可信基准版本，需用户明确核对后重置基准；普通草稿自动保存不能改变已有基准。发布响应丢失后的同版本重试仍幂等。
- 04：供应商生成状态与本地结果保存状态分开。已生成、未保存的文案由持久扫描恢复；脚本写入与 READY 标记在一个事务中完成。数据库唯一任务索引和任务行锁保证并发幂等。每 30 秒重试，连续 5 次失败记录保存失败，保留原结果快照。重试仅写本地结果，不重新调用生成服务或重复扣费。copy/asr 的 API 将待保存映射为 ARCHIVING，保存失败映射为 FAILED，并返回具体状态文案；产物未就绪不能转作品。link_parse 的媒体解析成功独立于可选简介保存，详情见下文。
- 05：视频草稿按公司、用户、new/task:id 分开存储；历史任务验证归属及类型。写入必须携带读取到的整数 revision，数据库条件更新防止乱序覆盖。旧客户端缺少版本的写请求被拒绝。草稿未提供的媒体字段保持原值。
- 06：数字秒的保存、恢复、生成映射由配套前端统一处理。

## 验证

- 财务真实 PostgreSQL：`FinRefundThreeDirectionIntegrationTest` 13 项通过，包含其他收款、其他退款、错误归属类型、同号更正、并发仅一个赢家、回滚不留孤立退款及幂等重试。
- 内容域回归：119 项通过，包含人物草稿 9 项、结果保存 3 项、视频草稿隔离 1 项及原有内容域用例。
- 结果保存故障注入：在脚本写入后、READY 标记前由数据库触发器抛异常，确认脚本写入回滚；解除故障后恢复成功。
- PostgreSQL 使用本机专用测试容器，每个测试 JVM 自动新建并清理独立数据库；未调用生产业务接口或付费生成服务。

运行命令（在平台程序目录；测试数据库地址替换为本机专用实例）：

```powershell
mvn -pl domains/franchise/franchise-content,domains/franchise/franchise-finance -am test '-Dtest=FinRefundThreeDirectionIntegrationTest,*Content*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dplatform.test.jdbc.url=jdbc:postgresql://127.0.0.1:55461/postgres' '-Dplatform.test.jdbc.username=postgres'
```

## 配套发布与迁移

三项新增迁移：`20261117.960` 正式人物版本、`20261117.961` 结果保存状态及唯一任务索引、`20261117.962` 视频草稿作用域和版本。应先发布包含迁移的后端，再发布配套前端；旧客户端编辑既有人物、保存视频草稿会因缺少版本而被保护性拒绝。

部署前核对历史脚本是否有重复任务关联：

```sql
SELECT task_id, COUNT(*) FROM fr_content_script
WHERE task_id IS NOT NULL GROUP BY task_id HAVING COUNT(*) > 1;
```

若有重复，应核对业务数据后处理，不能直接删除历史脚本；唯一索引迁移会拒绝有歧义的数据。已有成功且脚本完整的任务被标记 READY，其余由扫描恢复。结果保存失败经管理员确认故障已解除后，可按具体任务和公司将 `result_state` 重置为 PENDING、`result_attempts` 置 0、`result_retry_after` 置当前时间，再由原扫描补写，保留结果快照和生成、结算事实。

本轮仅提交源代码和验证记录，未部署这六项修复。

## PR #9 合并前 P1 修复

- 正式人物直接更新必须携带读取时的数字整数 `version`。不再使用数据库最新版本兜底；缺失、null 或过期返回 `PERSONA_VERSION_CONFLICT`，格式非法返回 `INPUT_INVALID`，均不改变数据库记录。有效请求仍按公司、ID、未删除条件和版本执行条件更新。
- 链接解析的媒体成功与简介保存分开。`link_parse` 成功后立即返回可用媒体，简介的 PENDING/FAILED 不阻断后续 ASR；简介缺失、null、空字符串或纯空白时直接完成本地处理，不创建空脚本。有简介仍按原事务和任务唯一键保存；copy/asr 仍必须有有效文案并保存成功才能对外可用。
- 修改前再次检查所有登记工作区。其他人物工作区的旧改动未包含本次版本修复；未修改这些工作区。

验证先复现失败再修复：无版本/null 的人物更新原先通过；空简介的结果保存原先抛异常，任务详情原先返回 ARCHIVING。修复后运行内容域及链接供应商解析回归 **137 项，全通过**，无跳过。

- 真实 PostgreSQL 验证 A 保存后，B 的旧版本、缺失版本和 null 请求拒绝且整行不变；B 重新读取后可保存。同版本并发提交只有一次成功；草稿发布 9 项原有回归保持通过。
- 结果保存 6 项测试包含空简介的音频/视频结果、重复执行不建空脚本、有简介只保存一次、文案生成/转写仍拒绝空结果，以及原有事务故障恢复。
- MockMvc 任务详情接口验证 link_parse 的 PENDING/FAILED/READY 均不改变媒体解析成功状态；真正解析失败仍返回失败；copy/asr 继续受持久保存状态约束。
- 配套前端 PR #10 增加实际页面方法测试：简介缺失/null/空/空白和可选简介各保存状态下，使用音频或视频 URL 发起 ASR，最后采用口播转写内容。

```powershell
mvn -pl domains/franchise/franchise-content -am test '-Dtest=*Content*Test,DiadiLinkClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dplatform.test.jdbc.url=jdbc:postgresql://127.0.0.1:55462/postgres' '-Dplatform.test.jdbc.username=postgres'
```

本次补修没有新增迁移；测试使用专用本地 PostgreSQL 和模拟供应商，不调用线上/付费接口。尚未部署，未进行 iOS/安卓真机验收。


## PR #9 持续集成

新增 `.github/workflows/backend-ci.yml`：面向 backend-main 的 PR、backend-main 推送及手动触发，使用 Ubuntu 24.04 / Java 17 和独立 PostgreSQL 17 服务。先构建并安装 reactor 依赖，再对财务、内容两个模块执行完整 `verify`（含全部供应商模拟测试），要求测试存在，并上传 Surefire 报告。每个测试 JVM 使用原有 TestPostgres 机制创建、销毁独立数据库；测试不接入生产数据、供应商凭据或付费服务。

工作流只有仓库读取权限，不部署服务；同一 PR 的新提交取消旧运行，Actions 依赖固定到已核对的提交。保留现有 real-acceptance 默认排除规则；没有为本次整改用例添加跳过条件。
