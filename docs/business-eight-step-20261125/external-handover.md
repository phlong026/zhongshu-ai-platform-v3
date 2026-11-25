# R10 外部成果移交、工程接收与实际开工

## 接口与页面

新页面 `/pages/external-handover/index?commissionId=...`。保留旧移交历史入口；EXTERNAL 委托不能调用旧 packet-submit / packet-return / packet-receive 命令。

全部接口的基址为 `/franchise/commissions/{commissionId}/external-handover`，使用当前登录租户、公司、岗位。ID 为不透明十进制字符串，revision/version 为数值，日期 `YYYY-MM-DD`。所有业务写操作必须有 `Idempotency-Key`；结果未知时保留原请求与原键，不重传旧的一次性上传 PUT。

| 方法 / 路径 | 请求 | 结果 |
| --- | --- | --- |
| GET 基址 | 无 | View；只读，不创建移交包 |
| POST 基址 | Submit | View；冻结一个移交版本 |
| POST `/return` | Decision | View；指定工程接收人退回 |
| POST `/receive` | Decision | View；接收并在同一事务建档或复用工程 |
| POST `/versions/{version}/assets/{slot}/download-tickets` | 无；slot 为 RESULT / DRAWING / REVIEW / CONFIRMATION | DownloadUrlView；绑定该版本和文件槽位 |

接收人列表复用 `GET /franchise/commissions/{id}/handover-receivers`，只显示同公司在岗工程师/项目经理，不能选择自己。新确认附件使用已验收 `BUSINESS_DOCUMENT`，PDF/JPEG/PNG，最多30MiB；上传及完成协议与 R07/R09 相同。

```text
Submit {
  revision, resultVersion, confirmationAssetId,
  confirmationDate, note, managerUserId, confirmed
}
Decision { revision, handoverVersion, reason, confirmed }
File { slot, assetId, title }
Packet {
  version, resultVersion, status, drawingVersion, confirmationDate, note,
  managerUserId, managerName, files[4], paymentPlan,
  submittedByUserId, createdAt, decisionNote, decidedByUserId, decidedAt
}
View {
  commissionId, customerId, customerName, mode:'EXTERNAL', status, revision,
  resultVersion, projectId, canSubmit, canDecide, allowedActions,
  currentPacket, packets
}
```

`allowedActions` 为 external-packet-submit / external-packet-return / external-packet-receive。前端只按服务端布尔值/动作开放操作。提交要求当前成果版本、四份真实资料、已发生的客户确认日期、1–1000字交底说明、有效接收人及 confirmed=true。退回必须填写1–500字原因；接收必须 confirmed=true。完成响应不应自行推算 revision。

业务经办与其负责人可提交；老板只读。工程接收人只能处理当前分派给自己的待接收版本。改派或工程转交后原接收人失权；当前项目负责人/经办工程师可读取已接收包。工程财务只可读取已建档工程对应的已接收包。历史包对当前客户可读的业务经办/负责人/老板可见。并非所有历史接收人永久可见。旧委托详情对没有客户读取权限的当前接收人仅返回必要委托元信息及可见移交包，旧提交、业务沟通、审批、下发清单和费用记录不随包外泄；客户可读岗位仍保留原历史。

无有效会话/撤权、公司停用、客户转交、移交重新指派、工程负责人转交时清空旧上下文和附件预览。400 为输入问题，409 为状态问题，1099000001 为版本冲突，1099000000 为同键不同内容，1099000002/404 为无权/不存在。冲突需重新 GET 及重新核对，不自动换键重放旧操作。

## 冻结与工程事实

每个外部包绑定不可变的 R09 成果版本及其需求版本，随包冻结需求版本的付款约定。DRAFT 表示沟通草案，不生成应收；CONTRACT 表示当时的正式合同快照，也不会重复生成应收。工程开工准备仍从 R12 当前有效的 SIGNED 合同读取正式付款计划，需要时重新确认最新版本。

接收只建档并初始化施工节点，不自动开工、不自动完成客户第八步。工程 `detail` 增加 `handoverMode`、`externalHandoverVersion`、`handoverPaymentPlan`、`canReadHandover`；付款快照仅对该工程当前负责人/经办工程师或现有财务、老板可见。其他工程岗保留原基础项目只读项，EXTERNAL 的 documents 为空、canReadHandover=false；前端只在此标记为 true 时加载外部移交详情。外部工程资料的 `fileRef` 是真实受控 assetId，不是 URL；使用 sourceCommissionId 和 externalHandoverVersion 申请上述范围下载票据。

实际开工继续使用工程原 start 接口，需要实际日期、验收通过且本公司所有的 ENG_SITE_PHOTO / ENG_DRAWING 开工凭据、完成的开工准备与真实收款门禁。相同请求重试不重复写开工记录，改日期/凭据/项目不能复用同键。客户 START 事实同时检查真实日期、DONE START 节点、对应 PASS 现场记录以及有效资产，不凭状态或随意字符串认定完成。

## 资产和总部只读

外部下载使用 EXT_* 槽位与移交版本绑定；不会扩展工程岗对所有 BUSINESS_DOCUMENT 的通用下载权限。发票据、读字节前及读字节后均重新验证当前客户/移交/工程归属，检查已验收资产所有者及内容摘要。

移交接受后真实 `fr_eng_document` 持久化纯数字 assetId；R02 总部 project → document → company → asset 的只读授权链直接识别这一形式，支持 BUSINESS_DOCUMENT。总部不进入普通业务写接口。R22 组合回归补总部读取新外部工程附件；总部客户外部委托详情另在 R22 接线。

## 本地验证范围

本机无 Maven / PostgreSQL。提交前执行 Java 17 语法检查、diff 检查；真实事务、并发、资产与租户回归加入后端 CI，CI 运行结果由主任务统一核实。

新增 `ExternalHandoverPgIntegrationTest` 的 17 项真实 PostgreSQL 场景，复用扫描后的真实资产及 CRM、合同、工程服务，包含完整八步、正式付款计划继承、收款前开工拒绝、移交版本冻结、读中客户/工程转交撤权、租户及任职失效、摘要不一致、事务回滚、并发重试、接收与退回竞态、旧入口拒绝、客户锁等待期间撤权、手工工程复用及锁顺序、旧开工命令兼容。对象存储用内存字节实现；收款测试沿用真实财务表写入的既有 BusinessReceiptPort 测试桩，财务凭证确认链由财务模块的专项用例覆盖。

`WorkflowSourceFactsPgIntegrationTest` 补充无效/异公司/畸形附件不能完成第八步的回归；不存在可用开工凭据时只返回待补录，不篡改工程实际日期。
