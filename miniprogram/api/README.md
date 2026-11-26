# miniprogram/api/ 数据访问层

当前小程序通过本目录连接同仓库 `backend-main` 的 B 平台。账号、客户、创作、设计委托、工程、财务与绩效已有正式 API 调用链；页面和 `utils/platform-*.js` 共同完成 DTO 适配。下文带日期的记录保留当时接入背景，历史模拟服务不能替代当前接口。

## 结构

| 文件 | 对应后端接口 | 说明 |
|---|---|---|
| `http.js` | — | wx.request 封装：token、`X-Post-Id` 当前岗位、`tenant-id` 多租户头（默认 1）、超时、错误与 401 处理 |
| `runtime-base.js` | — | 仅允许开发者工具读取接口基址覆写；真机和桌面微信固定使用 B 环境配置 |
| `auth.js` | `/platform/auth/account/matches`、`/platform/auth/account/login`、`/platform/auth/wechat-login`、`/platform/auth/wechat-bind`、`/platform/posts/mine` | 账号密码和微信共用同一用户，简称匹配、首次改密、我的任职与岗位切换；短信API仅兼容，不在页面展示 |
| `org.js` | `/platform/org/staff-accounts`，以及现有 `/platform/org/*` | 门店老板开员工独立账号、一次性交付凭据；总部成员仍使用原staff接口 |
| `todos.js` | `/platform/todos` | 当前岗位的待办（可办理在前） |
| `franchise/crm.js` | `/franchise/leads`、`/franchise/customers` | 客资、客户、八步跟进视图、跟进时间线、流程状态、跟进汇总、方案版本、责任转交。客户跟进只走八步流程，小程序不再调用旧的 `/follows` |
| `franchise/commission.js` | `/franchise/design-policies`、`/franchise/commissions` | 标准费用、委托状态机命令、费用审批 |
| `franchise/design.js` | `/franchise/design/cases`、`/franchise/design/cases/{caseId}`、`/franchise/design/cases/{caseId}/assets/{assetId}/download-tickets` | B 只读代理旧 AI 设计的公开户型；案例图片使用带 caseId 的独立通道，不走生成素材接口 |

### B 公开户型读取（2026-11-15）

设计首页按10条分页并支持继续加载，灵感库按20条游标分页，两处均支持触底续页。户型卡片完整适配图片，详情读取封面、独立立面和所有平面图。前端仅调用 B；由 B 携带自身租户配置读取 A 的公开资料，收藏仍保存于 B。封面/详情短链只保留在当前页面，不持久化签名 URL。案例 ID 和资产 ID 全程保留字符串，避免大整数精度损失。

### 人物库与数字人口播（2026-11-15）

创作工具人物库沿用公司 `personas` 档案，新增 `/personas/{id}/clones`、`/clones/quote`、`/clones/{id}/refresh`。上传视频制作数字分身、上传MP3克隆声音；不生成五视图。制作使用独立的公司积分科目 `avatar_clone`、`voice_clone`，须先发布有效价格。口播创建传 `avatarCloneId` 和 `voiceCloneId`，后端校验公司、人物、就绪和结算状态后解析实际资源标识，不能传人物显示名称冒充资源ID。

需求、设计源项目差异和启用条件见 [业务模块迁移需求与差异验收](../../docs/业务模块迁移需求与差异验收_20261115.md)。目前快速预算等功能尚未达到与原设计小程序完全一致的验收要求。

隔离联调构建使用 `https://platform.zszhj.com/admin-api`，保持微信合法域名校验。测试边界与实测结果见 [本轮联调证据](../../docs/full-business-audit-20261114/runs/AUDIT-20261114-R02/development-upload-20261115.md)；下文接入顺序保留 M2.7 历史背景，当前实现以源码为准。

## 历史接入顺序（M2 设计 §12 M2.7）

1. **账号与组织先行**：2026-11-16登录页使用 `auth.accountMatches` 定位候选、`auth.accountLogin` 验证密码，或 `auth.login` 使用微信登录。首次微信登录用官方手机号授权 code，由后端匹配后台预留手机号并自动关联同一用户，老板、负责人和员工一致；后续无需重复授权或手动绑定。`auth.myPosts` 获取有效岗位后才进入工作台，空岗位清会话，不回退旧本地身份。
2. 待办中心接 `todos.list`；
3. 业务主链按 crm（客资→客户→跟进→预算）→ commission（委托→审批→首尾款→移交→接收）逐页替换。

## 约定

- 后端地址：默认使用 B 独立入口 `https://platform.zszhj.com/admin-api`；storage `zs-api-base` 与 `zs-wallet-base` 仅在微信开发者工具中允许覆写。真机和桌面微信忽略历史覆写，平台接口固定使用环境配置，钱包接口由其派生为 `/app-api`；
- 登录态：storage `zs-api-token-v1`；当前岗位：storage `zs-api-post-v1`（即 X-Post-Id）；多租户：storage `zs-api-tenant-v1`（默认 1，即 tenant-id 头）；
- 写命令可传幂等键（`Idempotency-Key`），重试安全；
- 带版本写（编辑、转交、关联）须带后端返回的 `revision`，冲突时报「数据版本已过期，请刷新后重试」；
- 首次改密响应 `requirePasswordChange=true` 时不保存会话；再次提交原初始密码与新密码后，服务端才签发令牌。新密码须10–64字符、包含ASCII字母与数字，UTF-8不超过72字节。
- 本机默认账号仅保存 `zs-login-default-account-v1` 的账号标识和显示信息，不保存密码。新开员工初始密码仅在当前页面内存，离页、换岗或重新提交清除。
- 旧短信API保留兼容；新版页面不再发送验证码或短信登录请求。存量未绑定微信且无可用密码的账号须由总部先完成账号配置，不能靠客户端生成第二账号。
