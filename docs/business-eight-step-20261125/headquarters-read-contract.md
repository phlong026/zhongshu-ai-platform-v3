# R02 总部全品牌只读接口

## 权限与归属

仅当前有效的总部工作身份 `hq-admin`、`finance`、`finance-lead` 可使用 `/platform/headquarters`。服务端逐次核验登录用户、当前任职、实际总部组织、用户/部门状态以及 `system_dept.tenant_id`。总部可读当前租户各品牌数据；普通门店的 `OrgScopeRule`、客户归属和写权限保持现有规则。

客户、工程领域以 `company_id` 归属为准；财务旧表的 `tenant_id` 存在默认值 1，不能据此判断真实租户。所有总部查询先从真实部门/公司目录生成当前租户公司集合，再限定业务行的 `company_id`。手机号、联系人等业务详情仅此总部受控入口可见，同品牌地图使用独立 R03 白名单投影。

品牌以同租户 `pf_store_location`、`pf_map_brand`、老板账号的组织关联解析。每公司返回 `brandState`：`RESOLVED`、`AMBIGUOUS`、`UNASSIGNED`。多品牌冲突不猜测唯一品牌，品牌筛选不包含未解析公司；总部未按品牌筛选时仍能看到当前租户的这类公司，供核对资料。停用/删除公司不进入当前可读集合。

## 查询接口

所有响应沿用 `{code,data,msg}`，文件下载/导出直接返回字节。所有业务 ID 均用十进制字符串，金额为整数分。

| 方法与路径（前缀 `/platform/headquarters`） | 内容 |
| --- | --- |
| `GET /context` | 当前 tenant、总部 org、post、role，`readOnly:true`，品牌和公司筛选项 |
| `GET /summary?brandId=&companyId=` | 客户和工程数量 |
| `GET /customers` | 客户分页，含品牌/公司、责任人、联系方式、当前八步摘要 |
| `GET /customers/{id}` | 客户详情及只读 workflow |
| `GET /customers/{id}/workflow` | R05 完整八步视图，所有写/完成/重开标志为 false |
| `GET /customers/{id}/workflow-records?pageNo=1&pageSize=20` | 节点历史记录分页 |
| `GET /projects` | 工程分页 |
| `GET /projects/{id}` | 工程详情、计划节点、资料和现场记录第一页、合同、真实动态付款期次、交付结算状态 |
| `GET /projects/{id}/documents` | 工程资料分页 |
| `GET /projects/{id}/site-records` | 工程现场记录分页 |
| `GET /finance/{kind}` | `receipts`、`payables`、`payments`、`refunds` 四类分页 |
| `GET /finance/{kind}/{id}` | 对应财务详情 |
| `GET /companies/{id}/books?keyword=` | 公司账、项目账、前期账本列表 |
| `GET /companies/{id}/ledger?scope=company` | 沿用财务账本真实统计和条目；scope 也可为 `project:{id}`、`book:{id}` |

客户、工程、财务分页共用参数 `brandId,companyId,keyword,status,dateFrom,dateTo,pageNo,pageSize`。日期 `YYYY-MM-DD`；页码从 1 开始，单页 1–100，默认 20。关键词按字面匹配，不把 `%`、`_` 当作用户通配符。

分页统一返回 `list,total,pageNo,pageSize,readOnly`。workflow-records 沿用 R05 的 `PageResult`：`list,total`。

项目详情中的 `documents`、`siteRecords` 也是上述分页对象；不可将第一页当成全部资料。现场记录附件为 `assetIds:string[]`。资料只返回文件记录 ID、名称、类型、版本、资产 ID 和可发起受控下载标志，绝不返回原始 `file_ref`、对象 key、签名 URL。历史外部链接资料显示为无受控资产引用，不直接跳转或抓取。

`paymentStages` 按全部实际期次返回，不限定三期。字段包括 `stageId,stage,label,sequence,amountCents,grossCents,collectedCents,refundedCents,balanceCents,dueDate,ratioBps,trigger,nodeIndex,planVersion,status`。净收款等于已登记分笔减有效退款，收退款关联必须同公司。R11 元数据列通过行 JSON 读取，支持后端与迁移的滚动顺序；R11 前缺少的元数据为 null，不编造默认付款条件。

## 附件下载

| GET 路径 | 业务证明 |
| --- | --- |
| `/customers/{id}/workflow/{step}/assets/{asset}/content` | 手工节点当前/历史附件引用；`CONTRACT` 使用该客户同公司的真实已签合同当前附件 |
| `/projects/{id}/documents/{document}/content` | 工程资料记录；旧交底 JSON 引用同时核验所属委托、不可变下发清单和资产属主公司租户 |
| `/projects/{id}/assets/{asset}/content?section=` | section 为 `CONTRACT`、`SITE_RECORD`、`CLOSURE_OWNER`、`CLOSURE_FILE`、`CLOSURE_SETTLEMENT`，逐项核对项目真实字段/记录 |
| `/finance/{kind}/{id}/assets/{asset}/content` | 收款、付款或退款的真实凭证字段；应付不提供此凭证端点 |

这些端点直接返回已验收的 PDF/PNG/JPEG 字节，附 `Cache-Control:no-store` 与 `nosniff`，不签发可转发外链。下载前后均重新核验真实当前总部身份、业务引用、租户、资产属主的现有有效公司服务账号和 `ACCEPTED` 摘要/大小；不创建公司账户，不伪造门店身份，也不扩大通用资产权限。停止任职、换岗、删除资料、移出当前租户后，尚未完成的下载不能继续释放字节。

## 导出

`POST /exports?dataset=customers` 接收与列表相同的品牌、公司、关键词、状态和日期筛选。dataset 为客户、工程或四类财务类别。UTF-8 BOM CSV 最多 50000 行，超过必须缩小范围；全部字段按 CSV 引号规则转义，公式开头加文本前缀，金额转换为元。

导出不修改业务数据。发布字节前必须成功写入操作日志，记录操作者、总部岗位、租户、范围和行数，不记录客户内容或原始关键词。导出读取使用一致快照，审计前后另起 `READ_COMMITTED` 事务复核总部身份及导出涉及的全部公司归属/状态，避免快照掩盖导出期间的撤岗、停用或迁移租户。

此命名空间没有客户/工程/财务写接口，唯一 POST 是审计导出。

## 验证与依赖

依赖 R05 workflow reader 和 R07 受控附件策略；不依赖 R04 登录改造。不新增数据库迁移，保留 1046–1049 未使用。R11 后续迁移扩展期次元数据后自动返回。

专项测试覆盖 PostgreSQL 实际租户/公司/品牌 joins、三种总部角色、门店财务拒绝、伪造/撤销任职、切租户、分页、品牌冲突、工作流历史引用、签约证据、工程下发清单、所有真实期次、财务凭证、CSV 注入、五万行上限、审计失败及导出期间撤权。HTTP 测试验证查询绑定、文件响应和只读 verbs；资产测试验证实际字节、摘要和 I/O 后复核。

本地已通过 Java 17 语法解析及 `git diff --check`。本环境没有 Maven/PostgreSQL，不能将语法检查写成编译或 PG 测试通过；固定提交的 CI 才是运行验收。新增测试加入 `release-regression`，原 `LocalAssetServiceTest` 仍在资产全套与发布回归中运行。
