# miniprogram/api/ 数据访问层（M2.7）

小程序与后端（众墅之家AI赋能平台 · 后端程序）的数据访问层。按《后台改造方案》§6.3：
**新增本目录，不改现有 utils 与页面**；各模块逐步把本地存储换成接口调用，本地演示模式保留。

## 结构

| 文件 | 对应后端接口 | 说明 |
|---|---|---|
| `http.js` | — | wx.request 封装：token、`X-Post-Id` 当前岗位、`tenant-id` 多租户头（默认 1）、超时、错误与 401 处理 |
| `auth.js` | `/platform/auth/wechat-login`、`/platform/auth/sms/send`、`/platform/auth/sms/login`、`/platform/posts/mine` | 员工双通道登录（微信一键 + 手机号验证码）、我的任职与岗位切换 |
| `todos.js` | `/platform/todos` | 当前岗位的待办（可办理在前） |
| `franchise/crm.js` | `/franchise/leads`、`/franchise/customers` | 客资、客户、跟进、方案版本、责任转交 |
| `franchise/commission.js` | `/franchise/design-policies`、`/franchise/commissions` | 标准费用、委托状态机命令、费用审批 |

## 接入顺序（M2 设计 §12 M2.7）

1. **账号与组织先行**：登录页双通道（`auth.smsLogin` 手机号验证码 / `auth.login` 微信一键）→ `auth.myPosts` 列岗位 → `auth.switchPost`；✅ 已接入（2026-10-31）：登录页双通道 + 真实登录后按后端岗位接管全站 Tab 导航与落地页（`utils/workbench-nav.js` api 优先，回退 demo），身份页可切换后端岗位/退出后端账号；业务数据可见性仍走 demo 上下文，待业务接口逐页接入后收口；
2. 待办中心接 `todos.list`；
3. 业务主链按 crm（客资→客户→跟进→预算）→ commission（委托→审批→首尾款→移交→接收）逐页替换。

## 约定

- 后端地址：storage `zs-api-base`（默认 `http://127.0.0.1:48080/admin-api`），真机调试改为局域网地址；
- 登录态：storage `zs-api-token-v1`；当前岗位：storage `zs-api-post-v1`（即 X-Post-Id）；多租户：storage `zs-api-tenant-v1`（默认 1，即 tenant-id 头）；
- 写命令可传幂等键（`Idempotency-Key`），重试安全；
- 带版本写（编辑、转交、关联）须带后端返回的 `revision`，冲突时报「数据版本已过期，请刷新后重试」；
- 短信验证码：60s 一条、每日上限由后端限流；本地联调从 `/admin-api/platform/dev-setup/sms-captcha?mobile=` 读码（仅 local profile）；
- 本地演示模式（`utils/account.js` 的 loginDemo）保持不动，两套登录并存。
