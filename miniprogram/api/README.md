# miniprogram/api/ 数据访问层

当前小程序通过本目录连接同仓库 `backend-main` 的 B 平台。账号、客户八步跟进、创作、灵感库与免费快速预算、外部委托、工程、财务与绩效使用正式 API 调用链；页面和 `utils/platform-*.js` 共同完成 DTO 适配。本轮规则与逐项实施状态以[业务与工程统一整改总表](../../docs/roadmap/V3-business-engineering-remediation-20261127.md)为准。下文带日期的历史接入记录不代表当前部署或验收结果。

## 结构

| 文件 | 对应后端接口 | 说明 |
|---|---|---|
| `http.js` | — | wx.request 封装：token、`X-Post-Id` 当前岗位、`tenant-id` 多租户头（默认 1）、超时、错误与 401 处理 |
| `runtime-base.js` | — | 仅允许开发者工具读取接口基址覆写；真机和桌面微信固定使用 B 环境配置 |
| `auth.js` | `/platform/auth/account/matches`、`/platform/auth/account/login`、`/platform/auth/wechat-login`、`/platform/auth/wechat-bind`、`/platform/posts/mine` | 账号密码和微信共用同一用户，简称匹配、首次改密、我的任职与岗位切换；短信API仅兼容，不在页面展示 |
| `org.js` | `/platform/org/staff-accounts`，以及现有 `/platform/org/*` | 门店老板开员工独立账号、一次性交付凭据；总部成员仍使用原staff接口 |
| `todos.js` | `/platform/todos` | 当前岗位的待办（可办理在前） |
| `franchise/crm.js` | `/franchise/leads`、`/franchise/customers` | 客资、客户、八步跟进、时间线、状态、汇总、授权历史方案和责任转交；新小程序不调用旧 `/follows` |
| `franchise/commission.js`、`pages/external-entrust/api.js`、`pages/external-handover/api.js` | `/franchise/commissions`、`/franchise/customers/{customerId}/external-entrust` | 外部委托与冻结成果移交；旧委托原单读取、允许的历史结算和移交动作由服务端判定 |
| `franchise/design.js` 与 `design-migration/` 请求适配 | `/franchise/design/cases`、预算及历史设计相关接口 | 公开户型、收藏、免费参考预算、授权历史查询/取消/下载；案例图片使用含 caseId 的独立下载通道 |
| `franchise/sales.js` | `/franchise/sales/contracts`、付款期次及原单接口 | 正式施工合同、签约凭证和签约锁版计划；原回款、退款及历史交付查询 |
| `franchise/engineering.js`、`finance.js` | `/franchise/engineering`、财务原单接口 | 原工程的计划、实际施工、交付和财务核实；按原合同及付款期次追踪实收与余额 |

## 本轮接口边界（2026-11-27）

- 八步名称和稳定代码以统一总表第 2.1 节为准，显示“合同签约”“开工大吉”。正式报价、正式施工合同、实际开工分别读取原事实，不用预算保存、移交接收或 8/8 进度代替。签约时锁定付款计划，签后各岗位只读期数、金额、日期及触发节点。
- 新快速预算返回免费口径 `budget-free-v1`，不会创建积分预占；客户归档仍绑定所查看的冻结版本并由服务端读取金额。已有付费命令继续用原 body/key、价格快照和回执恢复，不能改成新免费命令。内容克隆等共享积分能力继续使用。
- AI 平面、立面、修改稿和设计备注润色的新生成已停用，前后端均拒绝；历史任务的查询、取消及授权下载保留。不要从历史迁移规格重新开放生成。
- 新委托使用现有外部委托接口。旧 LEGACY 提交、审批、设计完成和下发不能产生新流程；历史原单、真实费用、退款和附件保留。旧单仅执行服务端返回的 `allowedActions`，转换必须符合原状态和结算条件，移交中单不能一律 `convert-legacy`，已接收单沿用原工程。
- 实体费用模拟付款新写已拒绝；历史模拟回执不能计入实收。工程申报、财务核实及退款沿用原单，余额按有效净额读取。公司积分充值是独立能力，是否开放由后端配置决定，不能与实体费用混用。
- 旧手工 `/customers/{id}/schemes` 包装与旧四节点 `sales/deliveries` 新写分别由 BIZ-T07、ENG-E06 退役；按对应 PR 的配对版本发布。已有成功请求仅按原内容、原幂等键回读原单，历史查询保留；新流程继续走八步资料/正式报价/原工程，不创建第二套施工交付事实。旧四节点时间只说明历史登记。
- `/customers/{id}/follows` 的后端兼容暂保留，新小程序不再调用。BIZ-A55（旧跟进）及 BIZ-A28（旧方案兼容）各自所需的支持截止版本和目标环境零调用观察证据尚缺，静态搜索无引用不能替代兼容退出验收。再次咨询是否改变预约日期仍按 BIZ-T09 等待明确业务规则，不在接口文档中自定。

### B 公开户型读取

灵感库保留游标分页、户型图片、独立立面和全部楼层图。前端仅调用 B；由 B 携带自身租户配置读取 A 的公开资料，收藏仍保存于 B。封面/详情短链只保留在当前页面，不持久化签名 URL。案例 ID 和资产 ID 全程保留字符串，避免大整数精度损失。

### 人物库与数字人口播（2026-11-15）

创作工具人物库沿用公司 `personas` 档案，新增 `/personas/{id}/clones`、`/clones/quote`、`/clones/{id}/refresh`。上传视频制作数字分身、上传MP3克隆声音；不生成五视图。制作使用独立的公司积分科目 `avatar_clone`、`voice_clone`，须先发布有效价格。口播创建传 `avatarCloneId` 和 `voiceCloneId`，后端校验公司、人物、就绪和结算状态后解析实际资源标识，不能传人物显示名称冒充资源ID。

人物资源列表逐条返回 `canRename`、`canDelete`、`canSetDefault`，小程序按当前岗位权限展示对应操作。`PUT /franchise/content/clones/{id}/title` 接收 `{title}` 并返回更新的资源，名称去掉首尾空白后为 1–20 个 UTF-16 码元。`DELETE /franchise/content/clones/{id}` 移除资源并返回 `true`，已生成作品和历史任务保留。`PUT /franchise/content/clones/{id}/default` 按视频分身和克隆声音分别设默认。资源 ID 保留字符串。

设计能力最初接入过程见[历史迁移记录](../../docs/业务模块迁移需求与差异验收_20261115.md)，其中 AI 新生成和预算扣点说明已被本轮退役及免费规则替代。该历史记录不作为新能力启用或当前验收结论。

2026-11-15 的构建配置及当批结果保留在[历史联调证据](../../docs/full-business-audit-20261114/runs/AUDIT-20261114-R02/development-upload-20261115.md)。当前联调须按本次版本选择隔离测试环境并保留微信合法域名校验，不能沿用旧报告推断已通过本轮验收；下文接入顺序仅保留 M2.7 历史背景。

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
