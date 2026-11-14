# B COS 创建与隔离核验

日期：2026-11-14。用户授权使用同一腾讯云账号、上海地域，创建 B 独立私有桶并继续配置；不得影响 A。

## 已在云端完成

| 项目 | 实际读回结果 |
| --- | --- |
| 存储桶 | `zhongshu-platform-b-assets-1397776231` |
| 地域 | 上海 `ap-shanghai` |
| 控制台创建时间 | `2026-11-14 18:09:23` |
| 访问权限 | 私有读写，ACL 仅拥有者完全控制；后续添加仅限 B 桶的旧身份拒绝策略，详见下文 |
| 存储形态 | 单 AZ |
| 版本控制 | 已开启，灾备管理页面读回确认 |
| 默认服务端加密 | SSE-COS AES256，服务端加密页面读回确认 |
| 匿名访问 | HTTPS 请求桶根目录返回 `403` |
| CDN、全球加速、自定义域名、跨域 | 本次未配置 |
| 复制、生命周期 | 本次未配置，未设置历史版本删除 |

创建页显示“存储桶创建成功”，随后分别读取桶概览、ACL、灾备、加密和桶列表。最后桶列表共三项，旧 `zhongshu-design-1397776231`（广州）及 `zhongshu-design-assets-1397776231`（上海）仍在；本次没有编辑它们的设置、对象或授权。

控制台入口：<https://console.cloud.tencent.com/cos/bucket?bucket=zhongshu-platform-b-assets-1397776231&region=ap-shanghai&type=bucketoverview>。

## 已在云端创建的最小权限策略

| 策略 | 创建时间（控制台） | 范围 |
| --- | --- | --- |
| `BPlatformCosApiOnly` | 18:21:56 | 仅 `franchise/*` 的 PUT/HEAD/GET |
| `BPlatformCosProviderOnly` | 18:23:12 | 四个业务前缀的 PUT/HEAD/GET，仅 `moderation-input/*` 可 DELETE |
| `BPlatformCosBackupReadonly` | 18:23:57 | B 桶版本清单、版本控制与加密状态，以及读取对象用于 SHA-256 |
| `BPlatformCosSecurityAudit` | 18:24:36 | 只读桶 ACL、策略、版本控制和加密，不读取对象内容 |

四份策略均限定 `qcs::cos:ap-shanghai:uid/1397776231:zhongshu-platform-b-assets-1397776231/...`，没有 `cos:*`、GetService、其他桶或全局资源权限。每份策略在完成按钮可用且没有分析器错误后提交，并从自定义策略列表读回名称、描述和创建时间。

随后创建四个独立的仅编程访问子用户，分别直接关联唯一对应策略；详情页均读回 `组(0)`：

| 身份 | 子用户账号 ID | 唯一策略 | 关联时间 |
| --- | --- | --- | --- |
| `zhongshu-b-cos-api` | `100055125585` | `BPlatformCosApiOnly` | 18:27:51 |
| `zhongshu-b-cos-provider` | `100055125699` | `BPlatformCosProviderOnly` | 18:30:48 |
| `zhongshu-b-cos-backup` | `100055125768` | `BPlatformCosBackupReadonly` | 18:34:31 |
| `zhongshu-b-cos-audit` | `100055125769` | `BPlatformCosSecurityAudit` | 18:35:28 |

凭据保存在本机 `/Users/honor.pei/.config/zhongshu-platform-b/credentials/cos-{api,provider,backup,audit}.json`，文件 `0600`、目录 `0700`。API 和 Provider 的实际配置片段另存同一受限目录下的 `platform-cos.properties`、`provider-cos.properties`，它们不是完整生产配置；未传到服务器、未进入 Git。桶概览和 Provider 独立授权截图存于本目录，不含密钥。

官方文档部分例子使用 `appid/1397776231`，但当前 CAM 分析器实测拒绝该格式，提示 COS 使用 `uid/${appid}`。实际采用并通过校验的是 `uid/1397776231`，不是更换账号。

策略 JSON 与部署配置在 B 后端 `平台程序/deploy/platform/cos-policies/`，不含密钥。AI Runtime 仅接收短期签名 URL，不配置长期 COS 密钥。

## 应用配置及验证

- API 使用真实的 `zhongshu.franchise.asset.storage.cos.*` 配置；Provider 使用独立 `zhongshu.design.asset.storage.cos.*` 配置。
- 两者 SDK endpoint 均为 `https://cos.ap-shanghai.myqcloud.com`，bucket 固定为上述 B 桶，`path-style-access=false`。
- Runtime 唯一存储 origin 为 `https://zhongshu-platform-b-assets-1397776231.cos.ap-shanghai.myqcloud.com`，不是 SDK 的地域 endpoint。
- 发布门禁同时检查 API、Provider、备份桶和 Runtime origin；A 桶、其他地域/桶、通配 origin、额外路径/query 和多个 origin 均拒绝。
- 本地部署测试 `Ran 48 tests / OK (skipped=2)`；两个跳过项为本机 macOS 不适用的 Linux 挂载测试，本轮 COS 改动未涉及这些代码。
- 生产 API 资产适配器离线预签名行为测试通过；资产模块 `22 tests / 0 failures / 0 errors / 0 skipped`。最终独立审查 `P0/P1/P2 = 0/0/0`。
- 上述配置与门禁已在 B 分支提交 `1b230d73`，本地提交，未推送或部署。
- 新增 B 桶旧身份拒绝策略及文档提交 `67f846e6`，同样未推送或部署。

## 真实 COS 权限验证

`probes/` 保存脱敏 JSON 结果和测试版本 manifest，不含凭据或签名 URL。

| 身份 | 实际验证 | 结果 |
| --- | --- | --- |
| API | 两个 24 字节版本 PUT/HEAD/GET、哈希与版本一致、默认 AES256、预签名 host；匿名 GET、DELETE、其他前缀 PUT、版本枚举及 A 随机不存在 key 的 GET 均 403 | 28 项通过 |
| Provider | 四前缀小对象 PUT/HEAD/GET；临时审核对象 DELETE 产生标记，按旧版本仍可读；其他前缀 DELETE、franchise PUT、A 随机 key GET 拒绝 | 35 项通过 |
| 备份 | 版本枚举、逐版本下载及哈希、删除标记、版本控制与加密；PUT/DELETE 和 A GET 拒绝 | 64 项通过 |
| 安全审计（最终） | HEAD 桶、ACL、版本控制、加密；GET Policy=200 且精确双身份 Deny；对象读写/版本枚举及 A GET 拒绝 | 18 项通过 |
| 生产 Java 适配器 | 实际生产 `CosObjectStorageAdapter` 生成签名 URL，再真实执行 PUT 200、GET 200，59 字节哈希一致 | 通过 |
| COS 组件恢复 | 备份身份读 API 旧版本，API 身份写全新 key；大小、SHA、版本及 AES256 一致，不覆盖或删除源 | 8 项通过 |

API 与 Provider 初轮合成对象总计 200 字节，测试对象及其版本保留供备份验证。对 A 的探测仅为读取随机不存在 key，返回拒绝，不读取业务内容，不向 A 执行 PUT/DELETE。备份清单明确 `scope=deployment-probe-only`、`completeBucketInventory=false`、`freezeId=null`，不是正式冻结备份。

安全审计首轮 18 项中仅“无桶策略”的响应码语义判定失败：HTTP 404、空响应体，SDK 返回错误码 `Unknown`。原 FAIL 与 refined FAIL 均保留，没有把任意 404 强行视为通过。随后因下面真实的全局权限继承问题新增 B 限定 Deny，最终审计按“存在精确拒绝策略”的新合同实际 GET=200 并通过；这不是修改旧失败记录。

最终双身份报告为 `probes/cos-dual-principal-final-report-20261114.json`，最终审计为 `probes/cos-audit-probe-result-20261114-dual-principal-final.json`。中间单身份 PASS 仅为历史证据，不能冒充最终策略。组件恢复报告明确为同桶单组件 probe，不是独立备份或全栈灾备。

## 旧身份的全局权限继承与 B 桶拒绝规则

最终 ACL 控制台列出既有 `zhongshu-runtime`（`100054794778`）的 `QcloudCOSFullAccess`，资源为 `*`、动作为 `cos:*`。仅新增四份 B 最小权限 allow 不能阻止此旧身份继承对新 B 桶的访问。

另对 A 配置的非秘密标识做 SHA-256 比对：`/opt/zhongshu/env.merged` 的 `ZS_COS_SECRET_ID` 摘要为 `00e10698d9ab8fdcd7656918deff1a8bff39361cfd202f7199270d2e371f7662`，与 CAM `zhongshu-cos-backend`（`100053333114`）唯一 SecretId 的摘要完全相同；未输出标识原值或读取/复制 SecretKey。该身份仅关联 `ZhongshuCosAssetsReadWrite`，用户组为 0。旧 Runtime 身份的标识摘要与 A 主后台配置不同，不能仅凭名字认定 A 主后台使用它。

因此只在 **B 桶** 加入一个 `DenyLegacyAServiceIdentitiesOnB`：

- Principal 精确为主账号 `100045498012` 下的子用户 `100054794778`、`100053333114`。
- Action 为 `name/cos:*`，Effect 为 `Deny`，Resource 仅为 B 桶 `/*`。
- 不涉及四个 B 身份、root 管理员、A 桶或 A 的既有 CAM 策略。

初始 B 策略 JSON 为 `{}`。加入第一身份后，增加第二身份前再次 SDK GET 并备份旧策略，再合并该 statement；没有覆盖或丢弃其他规则。最终控制台及独立审计 SDK 均读回完全一致的双身份策略，四类 B 身份真实矩阵重新通过。规则 JSON 在 B 后端 `deploy/platform/cos-policies/bucket-deny-legacy-a-identities.json`。

验证边界：配置标识归属已核对，但未使用 A SecretKey 发起签名拒绝请求；报告保留 `realLegacySignedDenyRequestVerified=false`。同账号管理员仍有管理权；桶级拒绝也不屏蔽账号级 `GetService` 的桶名可见性，不能称为独立腾讯云账号隔离。后续正式验收仍需 A 实际身份的负向请求与 A 图片正常读取证据。

## A 保护复核

COS 配置后再次只读 SSH 核查：`zhongshu.service`、`zhongshu-runtime.service` 均 active，API actuator 与 Runtime health 均 HTTP 200；`/opt/zhongshu/yudao-server.jar` SHA-256 仍为 `a5e05c5b1b6317b3022c3c3057f45696a0222c0e9f9d532aa26609ae57401e91`。未部署 B、未重启或重载任何服务。健康结果只证明采样时服务可响应，不替代全部业务回归。

18:54 再核查，A API 的两个已知域名 health 均为 HTTP 200 / UP，匿名公开案例接口 HTTP 200。公开响应未包含可直接用于本次核验的 A 桶签名图片 URL，故没有声称图片 GET 已通过。A 当前主服务启动时间为 17:44:06，早于 18:09 的 B 桶创建，且不同于上午保护基线；本轮没有重启命令，原因未核实，应保留既有运维操作记录，不能从健康状态推断启动原因。

## 尚未完成

1. A 实际身份访问 B 的签名拒绝请求，以及 A 正常图片读取尚未实际验收；须通过既有授权路径完成，无需在聊天提供或复制 A SecretKey。
2. API、Provider、备份、审计已使用独立身份；后续维护不可复用 A 的身份/密钥，也不可关联带有其他权限的用户组。
3. 正式冻结窗口的可信完整对象版本清单、完整 B 服务接线和正式恢复演练尚未执行；当前 probe 清单不能直接用于生产发布门禁。
4. CORS 要等待 B 的真实 Web 域名，只允许实际需要的 origin；不设置通配符，不把当前仍指向 A 的 `api2` 当成 B 域名。
5. 同机独立业务盘、独立备份盘和可信存储证明仍是部署前置条件。没有部署 B、没有修改或重启 A 服务。

需要用户配合的具体材料：现有 `/mnt/datadisk0` 用途及可划给 B 的范围、与业务盘独立的备份盘资源、B 正式 Web/小程序域名；正式全栈演练前还需真实总部租户/权限/价格与外部服务验收条件。不会格式化或清理原有数据盘，也不会把 `api2` 当前指向 A 的入口直接接管为 B。

版本控制提供历史版本保护，但不等于独立备份或已完成恢复演练。删除临时审核对象会产生删除标记，旧版本仍保留，后续生命周期需要单独制定。
