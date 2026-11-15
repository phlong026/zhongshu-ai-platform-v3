# miniprogram/api/ 数据访问层（M2.7）

小程序与后端（众墅之家AI赋能平台 · 后端程序）的数据访问层。按《后台改造方案》§6.3：
**新增本目录，不改现有 utils 与页面**；各模块逐步把本地存储换成接口调用，本地演示模式保留。

## 结构

| 文件 | 对应后端接口 | 说明 |
|---|---|---|
| `http.js` | — | wx.request 封装：token、`X-Post-Id` 当前岗位、`tenant-id` 多租户头（默认 1）、超时、错误与 401 处理 |
| `runtime-base.js` | — | 仅允许开发者工具读取接口基址覆写；真机和桌面微信固定使用 B 环境配置 |
| `auth.js` | `/platform/auth/account/matches`、`/platform/auth/account/login`、`/platform/auth/wechat-login`、`/platform/auth/wechat-bind`、`/platform/posts/mine` | 账号密码和微信共用同一用户，简称匹配、首次改密、我的任职与岗位切换；短信API仅兼容，不在页面展示 |
| `org.js` | `/platform/org/staff-accounts`，以及现有 `/platform/org/*` | 门店老板开员工独立账号、一次性交付凭据；总部成员仍使用原staff接口 |
| `todos.js` | `/platform/todos` | 当前岗位的待办（可办理在前） |
| `franchise/crm.js` | `/franchise/leads`、`/franchise/customers` | 客资、客户、跟进、方案版本、责任转交 |
| `franchise/commission.js` | `/franchise/design-policies`、`/franchise/commissions` | 标准费用、委托状态机命令、费用审批 |
| `franchise/design.js` | `/franchise/design/cases`、`/franchise/design/cases/{caseId}`、`/franchise/design/cases/{caseId}/assets/{assetId}/download-tickets` | B 只读代理旧 AI 设计的公开户型；案例图片使用带 caseId 的独立通道，不走生成素材接口 |

### B 公开户型读取（2026-11-15）

设计首页最多预览四个户型，灵感库按游标分页。前端仅调用 B；由 B 携带自身租户配置读取 A 的公开资料，收藏仍保存于 B。封面/详情短链只保留在当前页面，不持久化签名 URL。案例 ID 和资产 ID 全程保留字符串，避免大整数精度损失。

隔离联调构建使用 `https://platform.zszhj.com/admin-api`，保持微信合法域名校验。测试边界与实测结果见 [本轮联调证据](../../docs/full-business-audit-20261114/runs/AUDIT-20261114-R02/development-upload-20261115.md)；下文接入顺序保留 M2.7 历史背景，当前实现以源码为准。

## 接入顺序（M2 设计 §12 M2.7）

1. **账号与组织先行**：2026-11-15登录页使用 `auth.accountMatches` 定位候选、`auth.accountLogin` 验证密码，或 `auth.login` 登录已绑定微信；未绑定先验证已有账号再调用 `auth.bindWechat`。`auth.myPosts` 获取有效岗位后才进入工作台，空岗位清会话，不回退旧本地身份。
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
