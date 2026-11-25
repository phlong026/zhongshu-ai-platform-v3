# 总部外部委托版本与附件只读接口

本补丁在 R22 汇总分支上独立实现，依赖 R09 的 1060 版本表、R10 的 1061 已接收外部移交包、R02 总部只读授权与资产字节读取。没有新增业务表或写入客户、委托、工程、收付数据。

## 页面数据

`GET /platform/headquarters/customers/{customerId}/external-entrust/{kind}`，`kind` 仅为 `requests` 或 `results`，分页 `pageNo=1`、`pageSize=20`，每页最多 100 条。

返回 `Page{list,total,pageNo,pageSize,readOnly:true}`。按版本号倒序，历史需求与成果均保留。每行包含：

- `version`：整数版本。
- `data`：原始登记内容，解析为 JSON 对象。
- `paymentPlan`：仅需求记录返回原提交时冻结的付款约定对象或 null；不生成正式应收。
- `attachments`：`[{assetId:字符串,title:字符串}]`，沿用现有受控资产引用。
- `createdAt`、`actorName`：提交时间与当前同租户经办人名称；已移除人员允许名称为空。

先验证真实在职总部管理员/财务，再通过客户详情推导公司。查询显式匹配租户、公司、客户、委托归属；最后再次检查任职及公司。普通门店财务、老板、销售与原设计行政不能使用该接口。

## 附件内容

`GET /platform/headquarters/customers/{customerId}/external-entrust/{kind}/{version}/assets/{assetId}/content`

该版本必须精确引用该资产；仅允许已扫描验收的 `BUSINESS_DOCUMENT`。归属公司、原始存储字节摘要及任职/租户权限在存储读取前后复核。响应为 PDF/JPEG/PNG 原始字节，设置 `no-store`、`nosniff`、附件头，不提供可转发外链，也不模拟门店工作身份。

读取成功且完成元数据操作审计后再复核授权，审计失败或期间撤权不释放字节。审计仅存客户/公司/版本/资产编号，不记录姓名、手机号、需求正文或文件内容。原 workflow 附件入口限定八步节点码，不能绕到外部版本授权。

总部现有工程资料端点 `/platform/headquarters/projects/{projectId}/documents/{documentId}/content` 继续支持十进制 `file_ref`。对于来自外部委托的工程，进一步要求资料属于该公司当前已接收的 EXTERNAL 移交包四槽，误挂的普通无关资产不可读取。

## 验证

新增 9 项真实 PostgreSQL 用例，覆盖分页历史、只读空查询、三类总部岗位、门店/设计行政拒绝、租户/公司/委托/版本精确关联、真实资产扫描及历史字节读取、错误资产公司/类型/未验收拒绝、读取或审计期间撤权，以及已接收工程的十进制资产引用。另有 3 项 HTTP/入口契约测试。已加入后端发布回归名单。

本地环境无 Maven/PostgreSQL；本次只执行 Java 17 语法解析及差异检查，真实测试执行结果以 CI 为准。
