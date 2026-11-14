# 赋能平台小程序 API 静态审计（2026-11-14）

仅只读扫描当前前端工作区；不是开发者工具请求成功或后端已部署证明。没有修改业务代码或执行业务写入。

- 仓库：`/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3`
- app.json 注册路由：35（含兼容跳转壳；同一路由的 view 子页面另计）
- API 目录：14 个 JS 文件（含 http 基础层），业务 API 文件 13 个。
- 默认 API：`https://api2.zszhj.com/admin-api`；`zs-api-base` storage 可覆盖。`api/README.md` 仍写默认 127.0.0.1，与实际实现不同。
- 本报告中“接入”表示源码存在调用，不保证请求/参数/响应/鉴权与运行后端一致。

## 主要结论

CRM、交易、委托、工程、财务、绩效、设计、内容任务的大部分主链有前端 API 调用封装，工程/老板工作台等已剔除大部分本地账簿回落。仍有明确未联通的界面功能与局部残留，不能按“页面存在 + API 文件存在”认定全量闭环。

| 项目 | 当前源码行为 | 证据 |
|---|---|---|
| AI 视频首尾帧 | 可选本机素材，但生成 payload 不含 firstFrame/lastFrame，也没有先上传 | `pages/ai-video/index.js:137-144` |
| AI 视频参考素材 | `references` 只有 role/name，无 assetId、URL 或文件字节 | `utils/platform-content.js:57-63`、`pages/ai-video/index.js:143` |
| 视频提示词优化 | 点击只提示“暂未开通”，无接口调用 | `pages/ai-video/index.js:122-128` |
| 内容素材库 | 固定三张包内示例图；不请求云端素材清单 | `utils/creation.js:1-5`、`pages/studio/index.js:19,456` |
| 发布账号 | 固定抖音/视频号两个展示账号，直接上送固定 accountId；不是服务端账号授权清单 | `utils/studio.js:5`、`pages/studio/index.js:377-378`、`pages/studio/index.wxml:63` |
| 声音试听 | 弹窗说明尚未配置试听音频，无试听调用 | `pages/studio/index.js:235` |
| 展陈服务下单/订单与甄选采购 | request/orders/order/plan/delivery/cancel 重定向展示 services；procure/purchases/purchase 重定向 selection | `pages/account/index.js:24-25` |
| 学习内容/资料 | 课程、资料目录硬编码；课程进度 GET/POST 已接入；不能把进度接入当课程资源后台化 | `pages/account/index.js:20-21,32-54,92-104` |
| 帮助反馈 | submitFeedback 只写本地 status=已保存待同步；检查 demo session，API-only 会话未满足这个检查 | `pages/account/index.js:118` |
| 旧管理配置 | 职责分工、规矩、门店配置保存仅提示未接入；入口不少已跳转成员/任务页 | `pages/admin/owner-page.js:231-243` |
| growth 历史列表/详情 | refresh 仍以 G.load 填 contents/leads/content/lead；在线只覆盖 summary/pending，capture/link/invalidate 单独接 API | `pages/growth/index.js:11-24,30,32-79` |
| 资料与消息弱网失败 | 部分 catch 空处理，可能没有控制台错误或页面提示；需 Network 判定 | `pages/account/index.js:109,114-117`、`pages/profile/profile-page.js:90-105` |
| 业务首页统计 | customers/commissions 只取第 1 页 50 条后本地算跟进/委托数据，超过 50 条的汇总可能低估 | `pages/business/index.js:30,44-58` |

## 角色与模块 -> 页面 -> API

| 角色/模块 | 主要页面 | API 文件/路径范围 |
|---|---|---|
| 登录/任职 | account?view=login/identity | `api/auth.js`：/platform/auth/wechat-login、/platform/auth/sms/send、/platform/auth/sms/login、/platform/posts/mine |
| 成员权限/离职 | account?view=api-members | `api/org.js`：/platform/org/teams、staff、posts、staff-status、staff-handover、staff-emergency-revoke、handover-pending |
| 全角色待办 | business、engineering、build/home、admin/home、finance-desk | `api/todos.js`：GET /platform/todos；由 `utils/platform-todos-page.js` 分发 |
| 资料/消息 | profile、account?view=profile/messages | 直接 http：GET/PUT /platform/profile/mine，POST /infra/file/upload，GET /platform/messages、unread-count，POST /platform/messages/{id}/read、read-all |
| 公司积分 | point-asset-card 全局组件 | `api/company-points.js`：GET /franchise/company-points/summary、my-usage |
| 业务/客资/客户 | business、customers、growth、workflow?view=customer/follow/transfer | `api/franchise/crm.js`：/franchise/leads*、/franchise/customers* |
| 签约交易/交付 | workflow 下需求/方案/报价/合同/收款/交付 views、quote-export | `api/franchise/sales.js`：/franchise/sales/requirements、schemes、quotes、contracts、stages、deliveries |
| 设计委托/总部设计管理员 | workflow/commission?view=admin/pricing 等 | `api/franchise/commission.js`：/franchise/design-policies*、/franchise/commissions*、/franchise/customers/{id}/commission |
| AI 设计 | design -> workflow/explore 灵感/平面/立面/预算 | `api/franchise/design.js`：/franchise/design/cases、favorites、assets、projects、ai-jobs、note-polish、budget* |
| 工程/项目经理 | engineering、build/home、projects、record 多 view | `api/franchise/engineering.js`：/franchise/engineering 项目、计划、节点、材料、供应、费用、现场记录、收款、交付结算等 |
| 财务 | admin/finance-desk 多 view | `api/finance.js`：/franchise/finance/receipts、payments、payables、refunds、expense-refunds、bank-*、reconciliation、project-accounts、revision(s) |
| 老板/绩效/提成/经营 | admin/home、finance、brief、tasks、commission | `api/franchise/performance.js`：/franchise/performance/goals、tasks、cycles、commission、operating |
| 创作/二创/口播/视频/作品/发布 | creation、studio 多 view、ai-video、growth | `api/franchise/content.js`：/franchise/content/links、scripts、tasks、drafts、video-draft、course-progress、assets、personas、publications、growth |
| 旧设计点充值 | wallet/recharge、wallet/records | `api/wallet.js`：POST /platform/wallet/session 桥接后，/app-api/design/v1/recharge-plans、recharge-orders、point-account、point-ledger；须与新公司积分分开核验 |

## 全 35 路由与调用入口

以下列出直接源码中的相关 require；纯路由壳本身不发业务请求，需追踪目标页面。

| 路由 | 相关调用入口 |
|---|---|
| `pages/home/index` | 静态展示或兼容跳转壳 |
| `pages/business/index` | `../../utils/dashboard`、`../../utils/platform-todos-page`、`../../api/http`、`../../utils/platform-crm`、`../../api/franchise/crm`、`../../api/franchise/commission`、`../../api/franchise/content`、`../../api/auth`、`../../utils/platform-content` |
| `pages/engineering/index` | `../../utils/dashboard`、`../../utils/platform-todos-page`、`../../api/http`、`../../api/auth`、`../../utils/platform-engineering` |
| `pages/management/index` | `../../api/http`、`../../api/auth` |
| `pages/profile/index` | `./profile-page` |
| `pages/customers/index` | `../../utils/platform-crm`、`../../api/http`、`../../api/auth`、`../../api/franchise/crm` |
| `pages/design/index` | `../../api/auth`、`../../api/http` |
| `pages/workflow/commission/index` | `../../../api/http`、`../../../api/auth`、`../../../api/franchise/commission` |
| `pages/workflow/quote-export/index` | `../../../api/http` |
| `pages/workflow/explore/index` | `../../../api/http`、`../../../api/auth`、`../../../utils/platform-design`、`./platform-bridge`、`../../../api/franchise/design` |
| `pages/workflow/index` | `../../utils/platform-crm`、`../../api/http`、`../../api/auth`、`../../api/franchise/crm`、`../../api/org`、`../../api/franchise/sales` |
| `pages/build/index` | `../../utils/platform-engineering` |
| `pages/build/home/index` | `../../../utils/platform-engineering`、`../../../api/auth`、`../../../api/http`、`../../../utils/platform-todos-page` |
| `pages/build/projects/index` | `../../../utils/platform-engineering`、`../../../api/auth` |
| `pages/build/materials/index` | 静态展示或兼容跳转壳 |
| `pages/build/record/index` | `../../../utils/platform-engineering`、`../../../api/franchise/engineering`、`../../../api/http`、`../../../api/auth` |
| `pages/account/sales/index` | 静态展示或兼容跳转壳 |
| `pages/account/index` | `../../api/auth`、`../../api/http`、`../../api/org`、`../../utils/platform-messages`、`../../api/franchise/content`、`../../utils/platform-profile` |
| `pages/admin/index` | 静态展示或兼容跳转壳 |
| `pages/admin/home/index` | `../owner-page` |
| `pages/admin/finance/index` | `../owner-page` |
| `pages/admin/performance/index` | `../owner-page` |
| `pages/admin/tasks/index` | `../../../utils/platform-performance`、`../../../api/org` |
| `pages/admin/profile/index` | 静态展示或兼容跳转壳 |
| `pages/admin/detail/index` | `../owner-page` |
| `pages/admin/finance-desk/index` | `../../../api/auth`、`../../../api/finance`、`../../../api/http`、`../../../utils/platform-finance`、`../../../utils/platform-todos-page`、`../../../api/franchise/performance`、`./platform-actions` |
| `pages/admin/operations/index` | 静态展示或兼容跳转壳 |
| `pages/admin/commission/index` | `../../../utils/platform-performance` |
| `pages/admin/brief/index` | `../../../utils/platform-performance`、`../../../utils/platform-operating-brief`、`../../../api/http` |
| `pages/growth/index` | `../../utils/platform-content`、`../../api/franchise/content`、`../../api/franchise/crm`、`../../api/http`、`../../api/auth` |
| `pages/studio/index` | `../../utils/platform-content`、`../../api/http` |
| `pages/creation/index` | `../../utils/platform-content`、`../../api/http` |
| `pages/ai-video/index` | `../../utils/platform-content`、`../../api/franchise/content`、`../../api/http` |
| `pages/wallet/recharge/index` | `../../../api/wallet` |
| `pages/wallet/records/index` | `../../../api/wallet` |


逐接口后端对应关系见 [backend-api-map.md](backend-api-map.md)。
