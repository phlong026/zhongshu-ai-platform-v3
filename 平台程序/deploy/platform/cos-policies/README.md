# B 上海私有桶最小权限

唯一目标为 `zhongshu-platform-b-assets-1397776231`（`ap-shanghai`）。策略文件不含凭据，不修改 A 桶。API、Provider、备份清单、桶安全审计应分别使用独立 CAM 身份，不加入带有额外权限的用户组，不复用 A 密钥。

| 文件 | 权限用途 |
| --- | --- |
| api-runtime.json | 仅 `franchise/*` 上传、读取和 HEAD |
| provider-runtime.json | `uploads/*`、`accepted/*`、`ai-quarantine/*`、`moderation-input/*` 上传、读取和 HEAD；仅临时审核前缀可删除 |
| backup-inventory-readonly.json | B 桶版本清单、版本控制与加密状态，以及下载版本内容以计算 SHA-256 |
| bucket-security-audit.json | 只读验证 B 桶 ACL、策略、版本控制及加密；不读取对象内容 |
| bucket-deny-legacy-a-identities.json | B 桶资源策略：明确拒绝两个旧 A 服务身份访问 B，不能作为 CAM 用户 allow 策略关联 |

AI Runtime 只使用 Provider 签发的短期 URL，不配置 CAM 身份或长期 COS 密钥。SDK endpoint 为 `https://cos.ap-shanghai.myqcloud.com`，virtual-host 对象 origin 为 `https://zhongshu-platform-b-assets-1397776231.cos.ap-shanghai.myqcloud.com`，不能混用。

指定 `versionId` 下载仍使用腾讯 COS `GetObject` 权限，不能照搬 AWS 的动作名称。版本控制开启后，`DeleteObject` 产生删除标记，历史对象仍占用空间。本次暂不配置生命周期；后续清理历史版本需要独立运维流程，业务身份没有桶管理权。

本目录策略为可审查授权合同；仅有文件不代表云端已创建、关联或实际验权成功。实际状态见主项目 COS 证据记录。密钥必须留在源码及发布目录之外的受限文件中，禁止写入这些 JSON、日志或聊天。

配置代码可以用 `git revert` 回退，云端副作用不会随 Git 回退。需要停用 B 存储访问时，只停用本次新建的 B 身份/密钥或解除其专用策略，保留 B 桶及历史版本以便恢复；不要删除桶或永久清理版本，也不要更改 A 身份与授权。密钥文件清理须在停用云端密钥并确认 B 不再使用后进行。

### 防止旧全局权限继承到新桶

控制台实测，既有 `zhongshu-runtime`（`100054794778`）持有 `QcloudCOSFullAccess`，会继承对新 B 桶的访问。A 主后台配置的 COS SecretId 摘要则匹配 `zhongshu-cos-backend`（`100053333114`）。因此 B 桶另有一个资源级 `Deny`，仅针对这两个明确子用户和 B 桶 `/*`，不更改任何 A 桶或 A CAM 策略。两个身份均不同于四个 B 身份。

该文件采用 COS Bucket Policy 的 `Statement`、`Principal`、`Effect`、`Action`、`Resource` 大小写。`name/cos:*` 仅出现在拒绝规则中，拒绝边界仍是唯一 B 桶；四份 CAM allow 不使用通配动作。更新前必须 GET、备份并合并现有规则，禁止盲目覆盖整份策略。不能为了回退 B 业务而删除这一隔离规则。

桶级拒绝不阻止账号级 `GetService`，同账号管理员和持有该权限的身份仍可能看到桶名。配置 ID 摘要匹配、策略结构读回和 B 身份回归不能代替使用 A 实际身份的负向签名验权；此项仍需在正式身份清单中记录。

2026-11-14 控制台策略分析器实测拒绝 `appid/1397776231`，提示 COS 资源第五段应使用 `uid/${appid}`。因此本目录使用 `uid/1397776231`，以当前 CAM 校验通过的格式为准；桶与账号归属不变。

官方依据：

- [用户策略及 appid 资源语法](https://cloud.tencent.com/document/product/436/68280)
- [COS 授权指引](https://cloud.tencent.com/document/product/436/31923)
- [版本列表](https://cloud.tencent.com/document/product/436/35521)
- [GET Object](https://cloud.tencent.com/document/product/436/7753)
- [读取版本控制](https://cloud.tencent.com/document/product/436/19888)
- [读取默认加密](https://cloud.tencent.com/document/product/436/40137)
- [Bucket Policy 及子用户主体](https://cloud.tencent.com/document/product/436/18023)
- [PUT Bucket Policy](https://cloud.tencent.com/document/product/436/8282)
