# B 业务资产与委托成果

B 的上传票据、原始字节上传、完成扫描和一次性下载均使用 B 自有资产表与存储。`DESIGN_DELIVERABLE` 只允许平台设计岗上传 PDF/PNG/JPG，最大20MiB；不修改旧 AI 设计应用资产政策。

`POST /admin-api/franchise/commissions/{commissionId}/dispatches/{dispatchSeq}/files/{slot}/download-tickets`，slot 为 RESULT、DRAWING、REVIEW、CONFIRMATION，返回 `{downloadUrl,expiresAt}`。资料属主及 assetId 从已持久化下发清单/移交包派生；不能由请求指定。当前业务经办、符合范围的门店老板、平台设计和已指派工程人员由 CommissionDeliverableAccessPort 再核验；客户确认件仅限门店。具体岗位及对象范围以该端口实现为准。

返回相对 B 地址 `/admin-api/franchise/assets/{id}/content?token=...`，下载仍须当前 Authorization、X-Post-Id 和 tenant-id。短票据绑定用户、岗位、消费组织、属主组织、委托/下发批次/文件槽位；消费前后重验有效范围，一次性使用。普通资产下载仍保持同属主组织及资产类型岗位矩阵，不能以工程身份泛读合同附件。

授权清单中的属主资源通过只读现有映射恢复，不能误用绑定当前员工组织的惰性建号入口。属主组织或映射停用时按资源不可访问拒绝，不抛系统异常、不切换消费人身份。业务/门店老板及属主总部设计可追溯正式下发历史；工程人员仍严格读取当前冻结版本及当前有效移交关系。

数据库迁移 `V20261115.984` 只在 B 资产目录执行。降级前停止 B 流量、备份库及资料；存在新成果时恢复 B 备份，不能删除资料强行恢复旧约束。签名URL和原始token不写日志或业务字段。

本地测试：LocalAssetServiceTest、AssetAccessServiceTest、AssetTypeBEndSnapshotTest、LocalAssetWiringTest。范围授权的真实PG行为在 commission 模块覆盖；本模块字节与票据行为使用隔离H2和内存对象存储，不能替代真实COS/开发者工具验证。
