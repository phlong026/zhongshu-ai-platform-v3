# R07 外部成果与节点附件

## 实现

本任务依赖 R05，新增独立 B 端类型 `BUSINESS_DOCUMENT`，允许 PDF、JPEG、PNG，单个不超过 30 MiB。客户端走现有上传票据、字节上传、complete 验收链，只有 ACCEPTED 资产才能进入客户节点；文件内容校验、图片元数据清理及不可变对象存储复用 LocalAssetService。

文件尚未关联客户时仅上传人能读。关联后，通过当前与历史节点快照确定客户归属，再依据当前客户 owner/team/老板权限判断可见性。更换附件不撤销历史文件的客户归属；原上传人失去客户权限后，也不能从通用资产接口读回文件。同一资产首次关联客户时使用事务 advisory lock 串行校验，禁止并发关联到两位客户。历史已跨客户引用的资产要求具备每位关联客户的可见权限，避免以旧共享引用扩大范围。

`POST /franchise/customers/{id}/workflow/steps/{code}/assets/{assetId}/download-tickets` 校验客户、节点和当前/历史引用后签发短期票据，返回 `{downloadUrl,expiresAt}`。票据绑定 customerId/stepCode，经 CustomerWorkflowAssetScopePort 在发票、读字节及 I/O 完成后三次复查范围。签票记一条命令审计；下载同时复查当前任职、客户归属与已验收状态，避免转交发生于 I/O 期间时泄露旧客户资料。

通用 `/franchise/assets` 入口受同一 AssetVisibilityPort 策略约束，不能绕过节点权限。策略按类型分派，服务器传入资源类型和创建人；没有客户策略时 BUSINESS_DOCUMENT 下载默认拒绝。原有 CONTENT_ASSET 策略及 DESIGN_DELIVERABLE 角色范围保留。

## 边界

资料只保存受控 assetId 与文件名，不接受任意外部 URL。07 签约附件和08开工凭据由各自业务模块查看。R05 保留的 DESIGN_REFERENCE 历史引用也受客户 ACL 约束，老板仅可通过客户节点范围票据查看已关联资料，原通用类型通道角色不放宽；未关联及无权查看客户的旧设计资料仍拒绝。新外部文件统一使用 BUSINESS_DOCUMENT。

总部附件由 R02 的独立总部字节端点承接：总部角色和租户校验后，经服务器解析客户引用与属主组织，不能伪造门店 actor 或扩大通用资产通道。可复用 `CustomerWorkflowAssetPolicy.requireReferenced(tenant,company,customer,stepCode,assetId)` 校验节点引用。

## 验证

`CustomerWorkflowAssetPgIntegrationTest` 使用真实 PostgreSQL/Flyway/命令审计/扫描器，存储仅用内存夹具，覆盖未关联文件私有、同店同事与跨店隔离、团队及老板只读、老板经客户范围票据查看旧 DESIGN_REFERENCE 而通用下载仍拒绝、跨租户、历史替换版本、客户转交与旧票据、I/O 期间撤权、大小/MIME/审核摘要、并发首次归属冲突。新增测试纳入 Backend CI；stacked PR 的 `task/**` base 同样运行现有门禁。

本地当前仅执行 Java 17 语法解析与 diff 检查，未执行依赖类型编译或 PostgreSQL 集成测试；这些结果需以对应提交的 CI 为准。
