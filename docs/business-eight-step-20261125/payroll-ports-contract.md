# R15 底薪、提成与人工核算端口

## 范围

复用现有财务身份、公司隔离、命令幂等及审计。新端口登记管理层提供的规则，以及财务逐项确认的员工月度金额；不新增自动工资公式，不推测税费、扣款、比例或发放条件，不生成应付或实际付款。

原项目三期提成和既有应付/支付核验链继续保留。新端口中的提成明细可以引用真实动态 `stageId`，保存当时的合同节点标签、计划版本、约定金额和净实收。它是人工核算依据，不宣称达到某个未知提成公式的自动发放条件。

普通角色边界：门店 `finance` / `finance-lead` 可登记；`boss` / `owner` 可读。总部不得使用普通门店写端点。所有普通操作绑定真实 CurrentPost、登录用户、当前租户、有效公司与任职，写入在加锁后和审计后再次复核。工资对象必须仍在本公司有效业务/工程岗位，且系统用户属于实际租户。历史记录保留员工当时姓名，不因转岗或离职改写。

## 数据模型

- 岗位族：`BUSINESS`（sales / sales-lead），`ENGINEERING`（engineer / project-manager）。
- 规则：按公司、岗位族递增 `version`，含 `effectiveMonth`、`baseSalaryNote`、`assessmentNote`、`commissionNote`、`evidenceNote`，固定 `calculationMode=MANUAL_ONLY`。
- 适用规则：选择不晚于核算月份的最近生效月，再取该月最大版本。后续规则从不覆盖历史核算引用的规则快照。
- 人工核算：按公司、员工、岗位族、月份递增版本。底薪、每项提成、其他调整均由财务明确填写；合计只进行金额相加。`snapshot` 保存规则全文和当时项目节点证据，`cashFact=false`。
- 未配置规则时返回 `UNCONFIGURED`，所有自动计算均不可用；允许明确勾选人工核对、填写依据后记录人工金额，快照仍注明“规则待配置”。

`V20261125.1048` 新建两个版本表，数据库拒绝更新或删除原版本；有历史数据时回滚脚本拒绝直接删表。1049 未使用。

## 门店 API

前缀 `/franchise/finance/payroll`，查询沿用 `franchise:fin:query`，写入沿用 `franchise:fin:register` 且服务层只允许财务两岗。

| 路径 | 用途 |
| --- | --- |
| `GET /context?month=YYYY-MM` | 当前公司、月份、两岗位族的适用规则及最新全局版本、本公司当前员工和工程候选、`manualOnly/canWrite` |
| `GET /rules?family=&pageNo=&pageSize=` | 全部不可变规则版本，family 可省略 |
| `POST /rules` | 登记管理层已确认的新规则版本 |
| `GET /records?month=&family=&pageNo=&pageSize=` | 每个员工/月份/岗位族的最新版本，避免更正版本在列表中重复算人数或金额 |
| `GET /latest?userId=&family=&month=` | 指定员工当月最后版本，返回 `{record:对象或null}`；不能只在前 20 条里推测版本 |
| `POST /records` | 保存人工核算或新的更正版本 |
| `GET /records/{id}` | 指定不可变版本完整快照 |
| `GET /records/{id}/versions?pageNo=&pageSize=` | 此员工/月份/岗位族全部历史版本 |
| `GET /projects/{id}/stages` | 当前公司真实工程的全部有效合同付款节点和净实收 |

所有分页默认 20，最大 100，返回 `{list,total,pageNo,pageSize,readOnly:true}`。金额与 ID 用十进制字符串输出；版本为整数。规则和核算记录均含 `id/companyId/family/version/createdAt`；核算还含 `userId/month/note/snapshot`。

`POST /rules` body：

```json
{"family":"BUSINESS","effectiveMonth":"2026-11","expectedVersion":0,"baseSalaryNote":"管理层提供的底薪约定","assessmentNote":"已确认的考核内容","commissionNote":"已确认的提成约定","evidenceNote":"确认日期、文件编号等依据","managementConfirmed":true}
```

`POST /records` body：

```json
{"userId":"员工ID","family":"BUSINESS","month":"2026-11","expectedVersion":0,"ruleId":null,"baseSalaryCents":300000,"adjustmentCents":0,"commissions":[{"projectId":"工程ID","stageId":"真实付款节点ID","amountCents":10000,"basis":"人工确认本项提成的依据"}],"note":"本月核对或更正说明","manuallyConfirmed":true}
```

例中金额只说明结构，不是默认规则。没有提成时 `commissions=[]`；不关联付款节点时 `stageId=null`，工程仍须属于本公司。没有当前适用规则时 `ruleId=null`；已有规则必须传当前适用规则 ID，规则变化返回 409。

两个写端点都需要 `Idempotency-Key`。同一键同一输入重放原版本；换输入复用同键拒绝。命令载荷使用字段顺序固定的 record，避免重启或多副本的 Map 顺序影响幂等摘要。同公司/岗位族规则以事务锁串行化；核算先锁同岗位族规则，再锁公司/员工/月/岗位族，避免规则在校验后、核算提交前切换，`expectedVersion` 防覆盖。原记录和命令审计同事务提交。

## HQ 集成端口

`franchise-api/PayrollReadPort` 由 `FinPayrollService` 实现：

```java
rulesAuthorized(long tenantId, long companyId, String family, int pageNo, int pageSize)
recordsAuthorized(long tenantId, long companyId, String month, String family, int pageNo, int pageSize)
recordAuthorized(long tenantId, long companyId, long recordId)
historyAuthorized(long tenantId, long companyId, long recordId, int pageNo, int pageSize)
```

返回上述相同 DTO。端口只读，前后以独立新事务检查 TenantContext 等于传入租户、公司真实目录仍属此租户且有效；调用层仍必须先使用 R02 HeadquartersReadGuard 鉴别真实 HQ 任职。没有普通请求参数 `companyId` 可直接扩展门店范围，没有伪造门店 post。

## 验证边界

11 个新增真实 PG 集成用例进入 release-regression 名单，覆盖未配置的人工模式、无支付副作用、规则/核算历史不改写、真实自定义节点净回款、其他公司/转岗员工、幂等、错误租户/停用公司、老板只读/伪造角色、非法金额/月、并发核算版本、核算提交期间规则升级等待及事务回滚。本地 Java17 语法解析与差异检查通过；完整类型编译和 PG 执行必须由固定 SHA CI 验证。

## 总部页面接入

总部管理员、总部财务及财务主管使用 `/platform/headquarters/companies/{companyId}/payroll` 下的 GET `/rules`、`/records`、`/records/{id}` 和 `/records/{id}/history`。目标必须为当前真实租户内有效门店公司；其他品牌可以读取，其他租户不能读取。未传 `family` 或 `month` 时保持 null，复用端口的不限筛选语义。

接口先核对总部当前有效任职和目标公司，再以明确的 tenant/company 调用只读端口；返回前重新核对任职与公司状态，响应标记 `readOnly=true`、`Cache-Control: no-store`。没有借用门店员工身份，也没有新增总部写入薪酬的接口。小程序在总部工作台先选门店公司，再打开薪酬记录页面；总部组织本身不作为门店薪酬目标。

新增的总部 PG/HTTP 用例覆盖三个允许角色、两个品牌、全部读取路由、无筛选参数、非总部/旧设计行政/跨租户拒绝，以及查询期间公司关闭或岗位撤销。当前已完成独立源码评审与 Java 语法检查，数据库执行结果仍须由该候选版本的 CI 给出。
