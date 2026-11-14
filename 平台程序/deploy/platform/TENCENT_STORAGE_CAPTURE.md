# 腾讯云 CBS 身份与挂盘证据采集器

该工具只为 B 同机存储审查采集腾讯云账号身份、目标 CVM 实例和该实例绑定的 CBS 原始响应。它不会创建、
扩容、挂载、格式化或删除云盘，不调用 KMS，不修改 A。云上只允许以下三个只读 action：

- STS `GetCallerIdentity`；
- CVM `DescribeInstances`，请求中 `InstanceIds` 精确包含一个目标实例；
- CBS `DescribeDisks`，使用 `instance-id` filter、`Limit=100` 完整分页。

程序复用本机 Maven 缓存中的腾讯云官方 Java common SDK `3.1.1280`，由 SDK 完成 TC3 签名。没有手写签名、
没有新依赖下载，也不会启用 SDK debug。固定 HTTPS endpoint/API version 如下：

| 服务 | endpoint | version |
|---|---|---|
| STS | `sts.tencentcloudapi.com` | `2018-08-13` |
| CVM | `cvm.tencentcloudapi.com` | `2017-03-12` |
| CBS | `cbs.tencentcloudapi.com` | `2017-03-12` |

## 凭据与运行

凭据必须位于操作者控制的本地常规文件，权限精确为 0600，不能是软链，最大 16 KiB：

```json
{"SecretId":"...","SecretKey":"...","Token":"...可选临时令牌..."}
```

仓库不保存该文件。正式运行必须使用由独立责任人创建、仅授予上述三个 action 的 B 只读 CAM 身份；不得复用
A 身份。运行命令只接受四个具名参数：

```bash
install -d -m 0700 /private/new-empty-evidence-dir
./deploy/platform/capture-tencent-storage.sh \
  --credential-file /private/path/tencent-readonly.json \
  --instance-id ins-xxxxxxxx \
  --region ap-shanghai \
  --output-dir /private/new-empty-evidence-dir
```

输出目录必须由操作者预先创建，且为非软链、空目录、权限精确 0700；采集器不自动 mkdir。所有 JSON 使用
`CREATE_NEW` 和 0600，绝不覆盖。SDK 异常正文、SecretId、SecretKey 和 Token 都不会打印或写入证据。
网络超时为连接 10 秒、读写 30 秒。

输出路径的每一级父目录都禁止软链。采集开始时固定 output directory 的 `fileKey`，三个 API 完成后及每次
写入前后都重新核对；文件只通过 Java `SecureDirectoryStream` 持有的目录 FD 相对 `CREATE_NEW`。目标 Linux
文件系统/JDK provider 若不支持该能力则 fail-closed，不回退到普通绝对路径写入；macOS APFS 可做 schema 与
拒绝路径离线测试，但不能作为 live 采集输出平台。

部署验证时，`evidence.json` 必须移到仓库外的 root-only 位置，以 root:root、0600 保存并计算原始字节
SHA-256；`B_SAME_HOST_TENCENT_CBS_EVIDENCE_FILE` 与 `B_SAME_HOST_TENCENT_CBS_EVIDENCE_SHA256` 必须同时
配置。验证器只接受完成时间不超过一小时、未来偏差不超过五分钟的证据。主机 IMDS 的 instance/region/zone
只作辅助现场核对，不由采集器写入云证据，也不能替代三个只读 API。

## 输出 schema

验证器消费的最终文件是 `evidence.json`（version `1`、provider `tencent-cbs`）；它在 raw 与辅助 manifest
之后最后写入，权限 0600，UTF-8 大小不得超过 1 MiB。其结构为：

- `version`、`provider`、`region`、`instanceId`、`accountUin`（STS `AccountId`）、`collectedAt`（完成 UTC）；
- `responses.getCallerIdentity`：`request: {}` 与单层 `response: {Response: ...}`；
- `responses.describeInstances`：`request: {InstanceIds:[instanceId]}` 与单层 Response envelope；
- `responses.describeDisks[]`：每个分页请求的精确 `Filters/Offset/Limit` 与对应单层 Response envelope。

SDK 已返回 `{Response: ...}` 时不会再次包裹；若返回内层 Response 对象，采集器只补一层。所有响应先经过
严格 JSON 解析，重复键、尾随内容、非法数字和字段类型不符均拒绝。

`capture-manifest.json` 是供人工追踪的辅助索引：

- `schemaVersion`：当前为 `1`；
- `captureStartedAt` / `captureCompletedAt`：UTC ISO-8601；
- `target`：`region`、`instanceId`；
- `callerIdentity`：`AccountId`、`UserId`、`PrincipalId`、`Type`、`Arn`、STS `RequestId`；
- `instance`：CVM 返回的唯一目标实例对象；
- `disks` / `diskCount`：CBS 完整分页返回且 `InstanceId` 精确匹配的磁盘对象；
- `calls[]`：固定 service/action/endpoint/apiVersion、每次请求的 `RequestId` 与 raw 文件相对路径。

原始响应以 `raw-*.json` 扁平放在同一 output 根目录：STS 和 CVM 各一个文件，CBS 每页一个文件。不存在运行期
创建的 raw 子目录；所有文件都经同一目录 FD 相对写入。响应保留云 API `RequestId`，但不包含本地
凭据。`TotalCount` 变化、空页提前出现、重复 `RequestId`、重复 `DiskId`、实例不匹配、分页未完成或输出冲突
都会 fail-closed，不产生可信 manifest。该证据只证明 API 所报告的云资源身份；不能凭 CBS 资源 ID 臆称物理
故障域独立、thick provisioning 或容量审批已经满足。

部署层默认仍为 `serial-wwn-thick-v1`；只有显式设置
`B_SAME_HOST_STORAGE_IDENTITY_MODE=tencent-cbs-v1` 才消费本证据。该模式的 host proof 与独立 attestation
使用 `allocationContract=managed-block-v1`。guest 目标设备 serial 匹配 CBS `DiskId` 是本项目的控制面/guest
绑定策略，不是腾讯云官方物理硬件或 thick 保证。

CBS `Encrypt` 必须作为布尔实况继续绑定到 host proof 与独立 attestation；验证器不会把未加密状态硬编码为
拒绝。`encrypted=false` 只可进入另经正式受信批准的受限试点候选，不能表述为已满足生产加密建议，也不能
据此自行开通 KMS。

## 离线验证

以下测试只用内存 fixture，不访问腾讯云：

```bash
./deploy/platform/test-tencent-storage-capture.sh
```

测试覆盖 101 块盘的两页采集、精确请求 filter/offset/limit、父目录软链、采集后目录替换、重复盘、跨实例盘、
分页缺口和 0644 凭据拒绝；支持 `SecureDirectoryStream` 的 Linux 还覆盖 0600/0700 与不可覆盖。正式 live
采集仍由获授权操作者单独执行，输出必须放在仓库之外。

官方资料：[GetCallerIdentity](https://cloud.tencent.com/document/product/1312/48195)、
[DescribeInstances](https://cloud.tencent.com/document/product/213/15728)、
[DescribeDisks](https://cloud.tencent.com/document/product/362/16315)、
[实例元数据](https://cloud.tencent.com/document/product/213/4934)。
