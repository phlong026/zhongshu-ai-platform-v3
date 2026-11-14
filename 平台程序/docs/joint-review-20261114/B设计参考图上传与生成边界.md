# B 设计参考图上传与生成边界（2026-11-14）

独立 remote 模式的 `/admin-api/franchise/design/assets` 上传票据、完成和下载操作统一使用 B 自有 AssetAccessPort、数据库和对象存储，不再转发 A 的素材接口。票据需提供 `assetType=DESIGN_REFERENCE`、mimeType、sizeBytes 和实际文件字节的 SHA-256。允许 JPG/PNG、最大 20MB，岗位仅 sales、sales-lead、hq-design-admin；老板、工程、财务不因本次变更获得设计权限。

上传协议保持原始字节 PUT：COS 签名地址不附带平台令牌；B 本地代理 `/admin-api/franchise/assets/{assetId}/content?token=...` 仍需有效平台会话与任职。HTTP、业务返回与 complete 的 ACCEPTED 均需校验后才允许保存引用。多租户、组织、service account、删除态和真实资产类型的复核保留。

**生成边界：**目前 A 的远程设计能力没有安全读取 B 素材的契约。立面生成请求携带非空 `requirementInputs.referenceAssetIds` 时，返回业务码 `1112100003`，在远程命令、积分预留和外呼之前拒绝。不能将 B assetId 作为 A assetId，也不能静默丢掉图片进行收费生成。跨应用参考图生成仍未实现；上传闭环不等于生成闭环。

数据库增量为 `V20261114.920__design_reference_asset.sql`，只扩展资产类型 CHECK；manifest 包含正向与 undo 文件 SHA-256。undo 检测到任何 DESIGN_REFERENCE 行（含已删除行）会主动拒绝，必须先保留对象及元数据并安排恢复方案，不能只 revert 代码。没有该类型数据时，可在独立 B 按迁移工具既有流程执行 undo。对象存储孤儿另需盘点。

验证：LocalAssetServiceTest 11 项、AssetTypeBEndSnapshotTest 2 项、DesignAssetIsolationTest 2 项在主代理最终定向回归中全部通过；设计测试验证远程与 legacy 资产零交互及付费前失败。未运行真实对象存储/真机大文件验收。本次未对共享 A 实施配置、JAR、数据库或服务重启操作。
