# R05 客户八步流程契约

## 范围

固定步骤代码：`INVITATION`、`INTRODUCTION`、`HOUSE_SELECTION`、`SITE_VISIT`、`MEASUREMENT`、`QUOTATION`、`CONTRACT`、`START`。首期一个客户一个在办流程，流程实例独立于工程项目。GET 不创建数据，首次有效写入以 revision=0 初始化。保存不完成；完成需验证；更正先重开并填写原因。历史 FOLLOWING 不补齐节点。记录可补录，当前步骤取首个未完成节点。

07、08 从合同/工程领域的事实查询端口派生，只读且不能手动勾选。旧 SIGNED 缺日期/附件标待补录；工程必须有实际日期及开工现场记录。跟进100%只表示跟进到开工，不改变施工进度或收款状态。

## API

- `GET /franchise/customers/{id}/workflow`：完整流程，未创建时 workflowId=null、revision=0。
- `PUT /franchise/customers/{id}/workflow/steps/{code}`：保存草稿。
- `POST /franchise/customers/{id}/workflow/steps/{code}/complete`：完成当前草稿。
- `POST /franchise/customers/{id}/workflow/steps/{code}/reopen`：重开已完成节点，reason 必填。
- `POST /franchise/customers/{id}/workflow/status`：ACTIVE/PAUSED/LOST，变更原因必填。
- `GET /franchise/customers/{id}/workflow/records?pageNo&pageSize`：版本/事件快照分页。
- `GET /franchise/customers/workflow-summaries?customerIds=1,2`：最多100个客户的批量进度，返回 customerId 为 key 的摘要对象；逐个验证可读范围。

写请求均带 `Idempotency-Key`（1-80字符）、流程 `revision`。保存体另含 `occurredOn`（YYYY-MM-DD）、`note`、`fields`、`attachments:[{assetId,title}]`。相同命令键重试不重复落记录；相同键不同客户/动作/内容拒绝；并发旧 revision 返回冲突。

新命令校验失败 `code=400`、版本或步骤状态冲突 `code=409`、范围校验 `code=404`；既有客户权限错误沿用领域错误码。幂等键冲突沿用 `1099000000`，原平台版本冲突码为 `1099000001`。写入失败原因在 CommonResult.msg，GET 的待补内容在 step.completionErrors。

## fields

| 步骤 | 字段 | 完成条件 |
| --- | --- | --- |
| INVITATION | contactMethod,invitationResult,appointmentAt,nextContactOn | 发生日期、联系渠道、CONFIRMED、约定到店时间 |
| INTRODUCTION | receptionist,requirements,feedback | 发生日期、接待人、需求、反馈 |
| HOUSE_SELECTION | referenceCaseId,referenceCaseName,selectionResult,versionLabel,confirmed | 发生日期、明确选择结果、confirmed=true，且有公开户型或附件 |
| SITE_VISIT | siteName,address,feedback | 实际日期、工地名称、反馈 |
| MEASUREMENT | address,measurer,width,depth,area,floors,checked,designContractAssetId,designContractName | 实测日期、地址、测量人、正数尺寸/面积、checked=true、附件 |
| QUOTATION | amountCents,scope,versionLabel,communicated,result,designFileAssetId,designFileName | 日期、正数金额（分）、范围、版本、已沟通、结果、报价附件 |
| CONTRACT | contractId,signedDate,totalCents,assetId,status | 服务端正式签约事实及必要证据，只读 |
| START | projectId,actualStartAt,managerUserId,status | 服务端实际开工及现场记录，只读 |

`invitationResult=CONFIRMED/PENDING/DECLINED`；`appointmentAt` 为本地 ISO 日期时间；数值允许数字字符串，保存时规范化，ID 一律字符串。附件 ID 必须为当前调用人有权引用的受控资产，不接受外部 URL。未填完可以保存草稿。

专用资料槽位（2026-11-27）：第 05 步 `designContractAssetId/designContractName` 存已签设计合同（选填），第 06 步 `designFileAssetId/designFileName` 存外部软件导出的设计文件（选填，是否作为完成条件待业务确认）。文件引用存在步骤 `fields` 里，与通用附件列表分开；保存时与附件走同一资产归属校验，下载票据同样接受 fields 引用。旧版保存若省略槽位编号则保留已登记的文件和名称；明确传空字符串才清除编号及名称；是否设为完成条件确定前，完成校验不检查槽位。

## 响应

完整流程包含 `workflowId,customerId,customerName,companyId,templateVersion,status,revision,completedCount,totalSteps,progressPercent,currentStepCode,nextStepCode,canWrite,steps,events,hasMoreEvents`。

步骤包含 `code,name,order,status,revision,occurredOn,note,fields,attachments,completedAt,canWrite,canComplete,canReopen,completionErrors,source`；状态为 TODO/DRAFT/COMPLETED，source 为 MANUAL/CONTRACT/ENGINEERING。

`events` 返回最近20条；records接口返回同形状 list/total。事件字段为 `id,stepCode,type,occurredOn,note,createdAt,actorUserId,actorName,revision,fields,attachments`。所有时间戳为 ISO UTC；前端按北京时间展示。

CustomerRow 新增 `workflow` 内联摘要，服务端批量查询，不逐行请求。摘要包含 `workflowId,completedCount,totalSteps,progressPercent,currentStepCode,currentStepName,nextStepCode,status,updatedAt,nextContactOn`。nextContactOn 取邀约节点最新记录的下次联系日期，未设置时可用旧 nextVisitDate 兜底。

07 的合同附件仅在 fields.assetId 中标识，通过合同模块原有受控入口查看；08 的开工凭据通过工程模块查看。两者不混入 BUSINESS_DOCUMENT 附件列表。sourceRevision 表示源记录版本；签约、开工只有日期时 completedAt 为 null，不虚构精确办理时间。

总部接口在外层验证总部管理员/总部财务后调用 CustomerWorkflowReadService.readAuthorized(tenantId,companyId,customerId)、summariesAuthorized(tenantId,companyId,customerIds)、recordsAuthorized(tenantId,companyId,customerId,pageNo,pageSize)。服务仍验证租户、公司和客户归属，始终返回只读权限。

## 验证

CustomerWorkflowIntegrationTest 使用真实 PostgreSQL/Flyway/组织数据范围/命令审计，覆盖只读 GET、旧数据不补齐、保存与完成分离、版本冲突、原键重试、跨客户复用键拒绝、并发首次写入、重开留痕、字段与资产验证、公开案例快照、负责人/公司/租户权限及真实源事实只读。WorkflowSourceFactsPgIntegrationTest 使用真实销售/工程表验证旧 SIGNED 缺证据不完成、START 日期/节点/现场记录匹配、增项排除以及租户/公司过滤。

当前工作容器没有 Maven/PostgreSQL/Docker，集成测试已编写但尚未执行；由任务分支 CI 运行这些用例后才能认定通过。

## 后续独立任务

R07 增加 BUSINESS_DOCUMENT（PDF/JPEG/PNG，30MB）和节点历史引用授权下载，下载入口为 `POST /franchise/customers/{id}/workflow/steps/{code}/assets/{assetId}/download-tickets`，返回 downloadUrl/expiresAt。R08 补齐正式签约证据输入校验。本任务先保留既有资产类型并拒绝无效引用，不改内部设计生成或支付。
