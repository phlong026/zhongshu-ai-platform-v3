# 小程序—后端接口静态映射审计（2026-11-14）

> 本文保留修复前的静态盘点与行号，不代表当前源码现状；历史调用点统计未在此重新计算。最新修复、真实请求和待部署状态见[逐项修复与四岗复测](repair-verification-results.md)及[汇总JSON](repair-retest-summary.json)。

## 结论与统计口径

- `miniprogram/api` 的统一 http/钱包固定路径：192 调用点，190 个去重方法+路径，完整后端源码均能找到同方法同路径的 Controller。
- 独立 B 运行边界中，有 9 个固定调用点/9 个去重路径属于旧 A、被关闭的桥接或仅融合入口的开发上传；因此 B 有对应启用源码的是 183 调用点、181 个去重路径。不能把“源码搜索全匹配”称作 B 全可用。
- api/finance.js:149 另有 1 个直接 wx.request 的 local/dev 上传回退，与 engineering.js:191 同一路径；计入后 api 目录为193固定调用点，仍190去重路径，其中183调用点/181去重路径具备B启用源码。
- 补查 pages/utils/components 的直接固定URL调用13处、9条去重路径，其中报价导出已在api表中，新增8条：7条B路由正常存在，1条参考图上传fallback拼接错误。合计206个固定调用点、198条去重路径，其中188条在当前独立B启用源码有对应；另10条属旧接口/开发回退/错误拼接。
- 这是固定HTTP路径映射，不是前端全部业务功能覆盖率。动态票据URL原始PUT、纯本地功能、调用未触发、外部AI/短信/微信能力和数据/权限正确性不由此证明；动态结果由主任务实际点击证据补充。

## 版本与运行边界

- 前端：`/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3`。
- 后端主仓根：`/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台`；remote 为 `https://github.com/phlong026/zhongshu-ai-platform-v3.git`；主仓 `feat/org-point-pool-20261113 @ f77fad1c`，`admin-ui/README.md` 有他人未提交修改。
- 本次独立B映射源码：`/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/.worktrees/b-independent-20261114/平台程序`；分支`feat/b-independent-20261114 @ e72e9e5116a0c316484bb517780a7b35c531714d`，工作区干净。完整源码映射也已对主仓同路径核对，未见缺路由。
- 运行18082的`/private/tmp/zs-ab-http-20261111-unmypre8/artifacts/B-final.jar`源于platform-split工作树的既有打包物。检查嵌套jar class，缺少新源码CompanyPointController，即公司积分summary/my-usage两接口不能用这个旧本地包验证。
- 旧包运行配置只读核对：`platform.legacy-design-bridge.enabled=false`、微信provider=stub、资产storage=local。因此它既不代表当前11月14日源码，也不代表用户当前实际访问的`https://api2.zszhj.com`服务版本。未重启、改配置或输出凭据。

## 关键问题

1. **钱包契约错位（P1）**：前端`api/wallet.js:22-25,68,143-194`仍走旧桥接与`/app-api/design/v1/*`7个端点。独立B `WalletController.java:33-34,55-105`已改为平台Bearer+岗位的`/admin-api/platform/wallet/*`；建单POST body `{planId,idempotencyKey}`、支付参数POST body `{code,idempotencyKey}`，账户`/account`、流水`/ledger`。旧桥接`WalletSessionController.java:32-33`只在legacy=true启用，而`PlatformEnvironmentPolicy.java:54`要求false。
2. **立面自传参考图阻断（P1，源码与隔离内存复现确认，开发者工具实测未完成）**：`pages/workflow/explore/platform-bridge.js:437`调用`http.upload("/app-api/design/v1/assets/.../content")`，而`api/http.js:187`拼`base()+path`，当前base=/admin-api时得到`/admin-api/app-api/design/v1/assets/.../content`。独立B无此路由；旧A该开发接口还需user_session且默认关闭，不能直接挪用。另`:438`的`res2/rej2`只在`:429`put闭包内定义；`:442-443`引用的`t`只在`:428`回调中定义。因此即便真实COS PUT成功，随后complete也会因t未定义而失败。主任务已使用当前源码提取uploadOne在node vm隔离复现：COS PUT成功后`ReferenceError: t is not defined`，local回退`ReferenceError: res2 is not defined`，两支completeCalls均0；证据`reference-upload-repro.json`，无外网或业务写入。真实COS PUT和local fallback应分别修复和在开发者工具验收。
3. **财务/工程local上传回退与B不匹配（条件性P1）**：`api/franchise/engineering.js:178-191`和`api/finance.js:130-152`对真实uploadUrl走原始字节PUT，生产COS正常支路不依赖旧dev接口；local/dev回退却调用融合入口`yudao-server`里的`/platform/dev/design/assets/{id}/bytes`。独立B不打包该类，而旧本地18082配置storage=local，故本地该回退链不能仅凭已有票据接口判通。
4. **本地包版本落后（联调阻断）**：新源码已有CompanyPointController和CompanyPointAccess实际服务，旧B-final.jar未包含。公司积分接口“源码有、当前旧包没有”，需与线上实际版本分开。
5. **发布范围边界**：ContentPublicationController.java:24,43及ContentPublicationService.java:222-229明确只做人工发布登记，拒绝scheduledAt；未实现外部账号授权/自动发帖。若界面有此承诺，不能凭publications路由存在认为自动发布已实现。

## 微信登录1104000002定位

- 主任务动态观察到`/platform/auth/wechat-login` HTTP200业务码1104000002；Controller路由存在（PlatformAuthController.java:39-50）。
- `RealWechatStaffClient.java:80-90` code2session上游非一次性code错误/空openid；`:123-131` stable_token异常/空token；`:96-107` getuserphonenumber上游错误；`:137-159`网络/超时/非200/解析失败，均可映射该码。需服务器warn中的API标签、errcode或异常类型才能确定实际分支。
- AppID/secret缺失在`:163-164`应走WECHAT_APP_NOT_ALLOWED，不应仅凭当前码断言“未填secret”。B application.properties:89-91配置键为PF_WX_PROVIDER(默认real)、PF_WX_STAFF_APPID、PF_WX_STAFF_APPSECRET。本子任务未检查线上配置/日志；主任务后续已用脱敏生产日志定位 code2session errcode=40125，详见 [runtime-login.md](runtime-login.md)。

## 模块计数（api目录统一调用口径）

| 模块 | 调用点 |
|---|---:|
| company-points.js | 2 |
| org.js | 9 |
| auth.js | 4 |
| todos.js | 1 |
| finance.js | 19 |
| wallet.js | 8 |
| franchise/crm.js | 10 |
| franchise/sales.js | 15 |
| franchise/design.js | 24 |
| franchise/commission.js | 7 |
| franchise/performance.js | 24 |
| franchise/content.js | 29 |
| franchise/engineering.js | 40 |

## api目录完整映射

| 前端位置 | 方法与路径 | 后端Controller位置 | 独立B边界 |
|---|---|---|---|
| api/auth.js:20 | POST /platform/auth/wechat-login | platform/platform-identity/src/main/java/cn/iocoder/yudao/module/platform/identity/controller/admin/PlatformAuthController.java:39 | 有启用源码 |
| api/auth.js:46 | GET /platform/posts/mine | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/MyPostController.java:64 | 有启用源码 |
| api/auth.js:60 | POST /platform/auth/sms/send | platform/platform-identity/src/main/java/cn/iocoder/yudao/module/platform/identity/controller/admin/PlatformAuthController.java:53 | 有启用源码 |
| api/auth.js:65 | POST /platform/auth/sms/login | platform/platform-identity/src/main/java/cn/iocoder/yudao/module/platform/identity/controller/admin/PlatformAuthController.java:61 | 有启用源码 |
| api/company-points.js:28 | GET /franchise/company-points/summary | domains/franchise/franchise-org/src/main/java/cn/iocoder/yudao/module/franchise/org/controller/admin/CompanyPointController.java:34 | 有启用源码；旧运行包缺class |
| api/company-points.js:37 | GET /franchise/company-points/my-usage | domains/franchise/franchise-org/src/main/java/cn/iocoder/yudao/module/franchise/org/controller/admin/CompanyPointController.java:40 | 有启用源码；旧运行包缺class |
| api/finance.js:24 | GET /franchise/finance/receipts | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:243 | 有启用源码 |
| api/finance.js:29 | GET /franchise/finance/payments | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:317 | 有启用源码 |
| api/finance.js:34 | GET /franchise/finance/payables | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:295 | 有启用源码 |
| api/finance.js:39 | GET /franchise/finance/refunds | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:349 | 有启用源码 |
| api/finance.js:44 | POST /franchise/finance/receipts | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:229 | 有启用源码 |
| api/finance.js:49 | POST /franchise/finance/payments | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:324 | 有启用源码 |
| api/finance.js:54 | POST /franchise/finance/refunds | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:365 | 有启用源码 |
| api/finance.js:59 | POST /franchise/finance/expense-refunds | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:390 | 有启用源码 |
| api/finance.js:64 | POST /franchise/finance/payables | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:302 | 有启用源码 |
| api/finance.js:69 | DELETE /franchise/finance/refunds/{id} | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:380 | 有启用源码 |
| api/finance.js:77 | DELETE /franchise/finance/payments/{id} | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:339 | 有启用源码 |
| api/finance.js:83 | GET /franchise/finance/reconciliation | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:461 | 有启用源码 |
| api/finance.js:88 | POST /franchise/finance/bank-confirms | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:478 | 有启用源码 |
| api/finance.js:94 | GET /franchise/finance/bank-flows | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:471 | 有启用源码 |
| api/finance.js:99 | GET /franchise/finance/project-accounts/{projectId} | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:492 | 有启用源码 |
| api/finance.js:127 | POST /franchise/assets/upload-tickets | domains/franchise/franchise-asset/src/main/java/cn/iocoder/yudao/module/franchise/asset/controller/admin/AssetAccessController.java:36 | 有启用源码 |
| api/finance.js:156 | POST /franchise/assets/{assetId}/complete | domains/franchise/franchise-asset/src/main/java/cn/iocoder/yudao/module/franchise/asset/controller/admin/AssetAccessController.java:42 | 有启用源码 |
| api/finance.js:237 | POST /franchise/finance/receipts/{receiptId}/revision | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:250 | 有启用源码 |
| api/finance.js:243 | GET /franchise/finance/revisions | domains/franchise/franchise-finance/src/main/java/cn/iocoder/yudao/module/franchise/finance/controller/admin/FinanceController.java:279 | 有启用源码 |
| api/franchise/commission.js:12 | GET /franchise/design-policies/current | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/DesignPolicyController.java:42 | 有启用源码 |
| api/franchise/commission.js:14 | POST /franchise/design-policies | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/DesignPolicyController.java:49 | 有启用源码 |
| api/franchise/commission.js:20 | POST /franchise/customers/{customerId}/commission | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/CommissionController.java:84 | 有启用源码 |
| api/franchise/commission.js:23 | GET /franchise/commissions | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/CommissionController.java:94 | 有启用源码 |
| api/franchise/commission.js:25 | GET /franchise/commissions/{id} | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/CommissionController.java:103 | 有启用源码 |
| api/franchise/commission.js:28 | POST /franchise/commissions/{id}/fee-decision | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/CommissionController.java:110 | 有启用源码 |
| api/franchise/commission.js:34 | POST /franchise/commissions/{id}/commands/{action} | domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/CommissionController.java:119 | 有启用源码 |
| api/franchise/content.js:15 | POST /franchise/content/links/parse | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentScriptController.java:42 | 有启用源码 |
| api/franchise/content.js:20 | POST /franchise/content/scripts/extract | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentScriptController.java:53 | 有启用源码 |
| api/franchise/content.js:22 | POST /franchise/content/scripts/optimize | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentScriptController.java:68 | 有启用源码 |
| api/franchise/content.js:24 | GET /franchise/content/scripts | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentScriptController.java:79 | 有启用源码 |
| api/franchise/content.js:25 | GET /franchise/content/scripts/{id} | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentScriptController.java:102 | 有启用源码 |
| api/franchise/content.js:30 | POST /franchise/content/tasks | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentTaskController.java:50 | 有启用源码 |
| api/franchise/content.js:32 | GET /franchise/content/tasks | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentTaskController.java:61 | 有启用源码 |
| api/franchise/content.js:34 | GET /franchise/content/tasks/{id} | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentTaskController.java:75 | 有启用源码 |
| api/franchise/content.js:36 | POST /franchise/content/tasks/{id}/save-work | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentTaskController.java:129 | 有启用源码 |
| api/franchise/content.js:38 | GET /franchise/content/tasks/{id}/result-url-status | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentTaskController.java:140 | 有启用源码 |
| api/franchise/content.js:43 | GET /franchise/content/drafts | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentDraftController.java:38 | 有启用源码 |
| api/franchise/content.js:45 | PUT /franchise/content/drafts/{workId}/status | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentDraftController.java:53 | 有启用源码 |
| api/franchise/content.js:47 | GET /franchise/content/video-draft | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentDraftController.java:63 | 有启用源码 |
| api/franchise/content.js:49 | PUT /franchise/content/video-draft | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentDraftController.java:84 | 有启用源码 |
| api/franchise/content.js:52 | POST /franchise/content/course-progress | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentDraftController.java:93 | 有启用源码 |
| api/franchise/content.js:55 | GET /franchise/content/course-progress | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentDraftController.java:104 | 有启用源码 |
| api/franchise/content.js:60 | POST /franchise/content/assets/upload-tickets | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentAssetController.java:52 | 有启用源码 |
| api/franchise/content.js:62 | POST /franchise/content/assets/{assetId}/complete | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentAssetController.java:65 | 有启用源码 |
| api/franchise/content.js:64 | POST /franchise/content/assets/{assetId}/download-tickets | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentAssetController.java:77 | 有启用源码 |
| api/franchise/content.js:69 | GET /franchise/content/personas | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPersonaController.java:38 | 有启用源码 |
| api/franchise/content.js:70 | POST /franchise/content/personas | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPersonaController.java:46 | 有启用源码 |
| api/franchise/content.js:71 | PUT /franchise/content/personas/{id} | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPersonaController.java:54 | 有启用源码 |
| api/franchise/content.js:72 | DELETE /franchise/content/personas/{id} | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPersonaController.java:63 | 有启用源码 |
| api/franchise/content.js:77 | POST /franchise/content/publications | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java:41 | 有启用源码 |
| api/franchise/content.js:79 | GET /franchise/content/publications | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java:51 | 有启用源码 |
| api/franchise/content.js:81 | POST /franchise/content/publications/{id}/verify | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java:60 | 有启用源码 |
| api/franchise/content.js:83 | POST /franchise/content/publications/{id}/link | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java:68 | 有启用源码 |
| api/franchise/content.js:85 | POST /franchise/content/publications/{id}/fail | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java:78 | 有启用源码 |
| api/franchise/content.js:90 | GET /franchise/content/growth/summary | domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java:88 | 有启用源码 |
| api/franchise/crm.js:9 | POST /franchise/leads | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/LeadController.java:42 | 有启用源码 |
| api/franchise/crm.js:11 | GET /franchise/leads/pending | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/LeadController.java:50 | 有启用源码 |
| api/franchise/crm.js:13 | POST /franchise/leads/{id}/link | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/LeadController.java:57 | 有启用源码 |
| api/franchise/crm.js:15 | POST /franchise/leads/{id}/invalid | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/LeadController.java:65 | 有启用源码 |
| api/franchise/crm.js:21 | GET /franchise/customers | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/CustomerController.java:61 | 有启用源码 |
| api/franchise/crm.js:23 | GET /franchise/customers/{id} | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/CustomerController.java:101 | 有启用源码 |
| api/franchise/crm.js:26 | PUT /franchise/customers/{id} | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/CustomerController.java:108 | 有启用源码 |
| api/franchise/crm.js:29 | POST /franchise/customers/{id}/follows | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/CustomerController.java:117 | 有启用源码 |
| api/franchise/crm.js:32 | POST /franchise/customers/{id}/schemes | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/CustomerController.java:125 | 有启用源码 |
| api/franchise/crm.js:35 | POST /franchise/customers/{id}/transfer | domains/franchise/franchise-crm/src/main/java/cn/iocoder/yudao/module/franchise/crm/controller/admin/CustomerController.java:134 | 有启用源码 |
| api/franchise/design.js:11 | GET /franchise/design/cases | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:50 | 有启用源码 |
| api/franchise/design.js:13 | GET /franchise/design/cases/styles | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:65 | 有启用源码 |
| api/franchise/design.js:15 | GET /franchise/design/cases/{caseId} | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:71 | 有启用源码 |
| api/franchise/design.js:17 | PUT /franchise/design/cases/{caseId}/favorite | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:78 | 有启用源码 |
| api/franchise/design.js:18 | DELETE /franchise/design/cases/{caseId}/favorite | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:86 | 有启用源码 |
| api/franchise/design.js:20 | GET /franchise/design/favorites | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:94 | 有启用源码 |
| api/franchise/design.js:25 | POST /franchise/design/assets/upload-tickets | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:107 | 有启用源码 |
| api/franchise/design.js:27 | POST /franchise/design/assets/{assetId}/complete | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:115 | 有启用源码 |
| api/franchise/design.js:29 | POST /franchise/design/assets/{assetId}/download-tickets | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:122 | 有启用源码 |
| api/franchise/design.js:34 | POST /franchise/design/projects | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:134 | 有启用源码 |
| api/franchise/design.js:36 | GET /franchise/design/projects | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:146 | 有启用源码 |
| api/franchise/design.js:38 | GET /franchise/design/projects/{projectId} | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:164 | 有启用源码 |
| api/franchise/design.js:40 | POST /franchise/design/projects/{projectId}/flat-jobs | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:176 | 有启用源码 |
| api/franchise/design.js:42 | POST /franchise/design/projects/{projectId}/elevation-jobs | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:191 | 有启用源码 |
| api/franchise/design.js:44 | GET /franchise/design/ai-jobs/{jobId} | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:203 | 有启用源码 |
| api/franchise/design.js:46 | POST /franchise/design/ai-jobs/{jobId}/cancellation-requests | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:209 | 有启用源码 |
| api/franchise/design.js:48 | POST /franchise/design/note-polish | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:219 | 有启用源码 |
| api/franchise/design.js:50 | POST /franchise/design/projects/{projectId}/flat-selections | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:233 | 有启用源码 |
| api/franchise/design.js:52 | POST /franchise/design/projects/{projectId}/elevation-selections | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:243 | 有启用源码 |
| api/franchise/design.js:54 | GET /franchise/design/projects/{projectId}/result-versions | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:262 | 有启用源码 |
| api/franchise/design.js:56 | POST /franchise/design/projects/{projectId}/revision-requests | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:273 | 有启用源码 |
| api/franchise/design.js:58 | POST /franchise/design/projects/{projectId}/save-to-customer | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:327 | 有启用源码 |
| api/franchise/design.js:64 | GET /franchise/design/budget/rule-info | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:290 | 有启用源码 |
| api/franchise/design.js:66 | POST /franchise/design/projects/{projectId}/budget-estimates | domains/franchise/franchise-design/src/main/java/cn/iocoder/yudao/module/franchise/design/controller/admin/DesignAccessController.java:303 | 有启用源码 |
| api/franchise/engineering.js:15 | POST /franchise/engineering/projects | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:48 | 有启用源码 |
| api/franchise/engineering.js:17 | GET /franchise/engineering/projects | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:60 | 有启用源码 |
| api/franchise/engineering.js:19 | GET /franchise/engineering/{id} | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:67 | 有启用源码 |
| api/franchise/engineering.js:22 | POST /franchise/engineering/{id}/manager | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:74 | 有启用源码 |
| api/franchise/engineering.js:27 | POST /franchise/engineering/{id}/prepare | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:37 | 有启用源码 |
| api/franchise/engineering.js:29 | POST /franchise/engineering/{id}/start | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:48 | 有启用源码 |
| api/franchise/engineering.js:31 | PUT /franchise/engineering/{id}/plan | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:58 | 有启用源码 |
| api/franchise/engineering.js:33 | POST /franchise/engineering/{id}/plan/reschedule | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:66 | 有启用源码 |
| api/franchise/engineering.js:35 | GET /franchise/engineering/{id}/plan/history | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:76 | 有启用源码 |
| api/franchise/engineering.js:38 | POST /franchise/engineering/{id}/nodes/{nodeId}/complete | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:83 | 有启用源码 |
| api/franchise/engineering.js:41 | POST /franchise/engineering/{id}/nodes/{nodeId}/reopen | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:94 | 有启用源码 |
| api/franchise/engineering.js:44 | POST /franchise/engineering/{id}/nodes/{nodeId}/correct | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngPlanController.java:103 | 有启用源码 |
| api/franchise/engineering.js:49 | POST /franchise/engineering/{id}/materials | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:42 | 有启用源码 |
| api/franchise/engineering.js:52 | PUT /franchise/engineering/materials/{materialId} | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:53 | 有启用源码 |
| api/franchise/engineering.js:55 | POST /franchise/engineering/supplies/{supplyId}/terms | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:62 | 有启用源码 |
| api/franchise/engineering.js:58 | POST /franchise/engineering/supplies/{supplyId}/arrival | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:73 | 有启用源码 |
| api/franchise/engineering.js:61 | POST /franchise/engineering/supplies/arrival-correction | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:86 | 有启用源码 |
| api/franchise/engineering.js:63 | POST /franchise/engineering/{id}/movements | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:97 | 有启用源码 |
| api/franchise/engineering.js:66 | POST /franchise/engineering/{id}/material-followups | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:107 | 有启用源码 |
| api/franchise/engineering.js:68 | GET /franchise/engineering/{id}/materials/summary | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngMaterialController.java:117 | 有启用源码 |
| api/franchise/engineering.js:71 | POST /franchise/engineering/{id}/expenses | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngExpenseController.java:65 | 有启用源码 |
| api/franchise/engineering.js:73 | POST /franchise/engineering/expenses/{expenseId}/terms | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngExpenseController.java:76 | 有启用源码 |
| api/franchise/engineering.js:75 | POST /franchise/engineering/expenses/{expenseId}/revise | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngExpenseController.java:85 | 有启用源码 |
| api/franchise/engineering.js:78 | POST /franchise/engineering/expenses/{expenseId}/cancel | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngExpenseController.java:93 | 有启用源码 |
| api/franchise/engineering.js:80 | GET /franchise/engineering/{id}/expenses | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngExpenseController.java:103 | 有启用源码 |
| api/franchise/engineering.js:86 | POST /franchise/engineering/{id}/site-records | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:86 | 有启用源码 |
| api/franchise/engineering.js:89 | POST /franchise/engineering/site-records/{recordId}/correct | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:104 | 有启用源码 |
| api/franchise/engineering.js:90 | GET /franchise/engineering/{id}/site-records | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngProjectController.java:97 | 有启用源码 |
| api/franchise/engineering.js:95 | GET /franchise/engineering/{id}/collections | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngCollectionController.java:40 | 有启用源码 |
| api/franchise/engineering.js:97 | POST /franchise/engineering/{id}/collections | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngCollectionController.java:48 | 有启用源码 |
| api/franchise/engineering.js:100 | POST /franchise/engineering/{id}/dunning | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngCollectionController.java:58 | 有启用源码 |
| api/franchise/engineering.js:102 | GET /franchise/engineering/{id}/followups | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngCollectionController.java:67 | 有启用源码 |
| api/franchise/engineering.js:107 | POST /franchise/engineering/{id}/deliver | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngClosureController.java:36 | 有启用源码 |
| api/franchise/engineering.js:109 | POST /franchise/engineering/{id}/settle | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngClosureController.java:47 | 有启用源码 |
| api/franchise/engineering.js:111 | POST /franchise/engineering/{id}/archive | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngClosureController.java:56 | 有启用源码 |
| api/franchise/engineering.js:113 | GET /franchise/engineering/{id}/settle-summary | domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/controller/admin/EngClosureController.java:64 | 有启用源码 |
| api/franchise/engineering.js:119 | POST /franchise/assets/upload-tickets | domains/franchise/franchise-asset/src/main/java/cn/iocoder/yudao/module/franchise/asset/controller/admin/AssetAccessController.java:36 | 有启用源码 |
| api/franchise/engineering.js:120 | POST /franchise/assets/{assetId}/complete | domains/franchise/franchise-asset/src/main/java/cn/iocoder/yudao/module/franchise/asset/controller/admin/AssetAccessController.java:42 | 有启用源码 |
| api/franchise/engineering.js:122 | POST /franchise/assets/{assetId}/download-tickets | domains/franchise/franchise-asset/src/main/java/cn/iocoder/yudao/module/franchise/asset/controller/admin/AssetAccessController.java:48 | 有启用源码 |
| api/franchise/engineering.js:191 | POST /platform/dev/design/assets/{assetId}/bytes | yudao-server/src/main/java/cn/iocoder/yudao/server/dev/DesignDevController.java:144 | 旧A/关闭桥接/开发回退 |
| api/franchise/performance.js:15 | GET /franchise/performance/goals | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfGoalController.java:36 | 有启用源码 |
| api/franchise/performance.js:17 | PUT /franchise/performance/goals | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfGoalController.java:43 | 有启用源码 |
| api/franchise/performance.js:22 | POST /franchise/performance/tasks | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:37 | 有启用源码 |
| api/franchise/performance.js:24 | GET /franchise/performance/tasks | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:50 | 有启用源码 |
| api/franchise/performance.js:28 | GET /franchise/performance/tasks/summary | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:60 | 有启用源码 |
| api/franchise/performance.js:31 | POST /franchise/performance/tasks/{id}/reassign | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:67 | 有启用源码 |
| api/franchise/performance.js:34 | POST /franchise/performance/tasks/{id}/manual-complete | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:77 | 有启用源码 |
| api/franchise/performance.js:37 | POST /franchise/performance/tasks/initialize-previous | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:88 | 有启用源码 |
| api/franchise/performance.js:39 | POST /franchise/performance/tasks/archive | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:96 | 有启用源码 |
| api/franchise/performance.js:41 | POST /franchise/performance/tasks/archive/revise | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:105 | 有启用源码 |
| api/franchise/performance.js:43 | GET /franchise/performance/tasks/archives | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfTaskController.java:114 | 有启用源码 |
| api/franchise/performance.js:49 | POST /franchise/performance/cycles/submit | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCycleController.java:37 | 有启用源码 |
| api/franchise/performance.js:52 | POST /franchise/performance/cycles/review | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCycleController.java:47 | 有启用源码 |
| api/franchise/performance.js:55 | POST /franchise/performance/cycles/appeal-resolve | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCycleController.java:57 | 有启用源码 |
| api/franchise/performance.js:58 | GET /franchise/performance/cycles | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCycleController.java:69 | 有启用源码 |
| api/franchise/performance.js:63 | GET /franchise/performance/commission/rows | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCommissionController.java:37 | 有启用源码 |
| api/franchise/performance.js:66 | POST /franchise/performance/commission/policy | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCommissionController.java:44 | 有启用源码 |
| api/franchise/performance.js:70 | POST /franchise/performance/commission/release | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCommissionController.java:60 | 有启用源码 |
| api/franchise/performance.js:73 | POST /franchise/performance/commission/reconcile | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfCommissionController.java:70 | 有启用源码 |
| api/franchise/performance.js:78 | GET /franchise/performance/operating/analytics | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfOperatingController.java:37 | 有启用源码 |
| api/franchise/performance.js:80 | POST /franchise/performance/operating/snapshot | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfOperatingController.java:44 | 有启用源码 |
| api/franchise/performance.js:82 | GET /franchise/performance/operating/snapshots | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfOperatingController.java:53 | 有启用源码 |
| api/franchise/performance.js:84 | POST /franchise/performance/operating/reconcile | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfOperatingController.java:60 | 有启用源码 |
| api/franchise/performance.js:86 | GET /franchise/performance/operating/reconciliations | domains/franchise/franchise-performance/src/main/java/cn/iocoder/yudao/module/franchise/performance/controller/admin/PerfOperatingController.java:68 | 有启用源码 |
| api/franchise/sales.js:11 | POST /franchise/sales/requirements | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:82 | 有启用源码 |
| api/franchise/sales.js:13 | GET /franchise/sales/requirements | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:93 | 有启用源码 |
| api/franchise/sales.js:18 | POST /franchise/sales/schemes/{schemeId}/promote | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:233 | 有启用源码 |
| api/franchise/sales.js:23 | POST /franchise/sales/quotes | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:107 | 有启用源码 |
| api/franchise/sales.js:25 | GET /franchise/sales/quotes | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:120 | 有启用源码 |
| api/franchise/sales.js:27 | GET /franchise/sales/quotes/{quoteId}/export-sheet | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:127 | 有启用源码 |
| api/franchise/sales.js:32 | POST /franchise/sales/contracts | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:142 | 有启用源码 |
| api/franchise/sales.js:34 | GET /franchise/sales/contracts | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:160 | 有启用源码 |
| api/franchise/sales.js:36 | POST /franchise/sales/contracts/{contractId}/sign | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:181 | 有启用源码 |
| api/franchise/sales.js:38 | POST /franchise/sales/contracts/{contractId}/void | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:193 | 有启用源码 |
| api/franchise/sales.js:43 | POST /franchise/sales/stages/{stageId}/collect | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:210 | 有启用源码 |
| api/franchise/sales.js:45 | GET /franchise/sales/contracts/{contractId}/stage-summary | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:224 | 有启用源码 |
| api/franchise/sales.js:50 | POST /franchise/sales/deliveries | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:248 | 有启用源码 |
| api/franchise/sales.js:52 | POST /franchise/sales/deliveries/{deliveryId}/advance | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:265 | 有启用源码 |
| api/franchise/sales.js:54 | GET /franchise/sales/deliveries | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:282 | 有启用源码 |
| api/org.js:9 | GET /platform/org/teams | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:117 | 有启用源码 |
| api/org.js:14 | GET /platform/org/staff | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:150 | 有启用源码 |
| api/org.js:19 | POST /platform/org/staff | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:169 | 有启用源码 |
| api/org.js:24 | POST /platform/org/posts | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:182 | 有启用源码 |
| api/org.js:29 | DELETE /platform/org/posts/{postId} | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:193 | 有启用源码 |
| api/org.js:34 | POST /platform/org/staff-status | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:204 | 有启用源码 |
| api/org.js:39 | POST /platform/org/staff-handover | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:239 | 有启用源码 |
| api/org.js:44 | POST /platform/org/staff-emergency-revoke | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:248 | 有启用源码 |
| api/org.js:49 | GET /platform/org/handover-pending | platform/platform-authz/src/main/java/cn/iocoder/yudao/module/platform/authz/controller/admin/StaffOrgAdminController.java:257 | 有启用源码 |
| api/todos.js:8 | GET /platform/todos | platform/platform-workflow/src/main/java/cn/iocoder/yudao/module/platform/workflow/controller/admin/PlatformTodoController.java:29 | 有启用源码 |
| api/wallet.js:68 | POST /platform/wallet/session | domains/franchise/franchise-org/src/main/java/cn/iocoder/yudao/module/franchise/org/controller/admin/WalletSessionController.java:39 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:147 | GET /design/v1/recharge-plans | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppRechargePlanController.java:36；yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/admin/RechargePlanAdminController.java:35 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:157 | POST /design/v1/recharge-orders | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppRechargeOrderController.java:56 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:163 | GET /design/v1/recharge-orders/{orderId} | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppRechargeOrderController.java:93；yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/admin/RechargeOrderAdminController.java:118 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:168 | GET /design/v1/recharge-orders | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppRechargeOrderController.java:79；yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/admin/RechargeOrderAdminController.java:35 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:175 | GET /design/v1/recharge-orders/{orderId}/pay-params | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppRechargeOrderController.java:111 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:181 | GET /design/v1/point-account | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppPointController.java:52 | 旧A/关闭桥接/开发回退 |
| api/wallet.js:186 | GET /design/v1/point-ledger | yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/app/AppPointController.java:68；yudao-module-commerce/src/main/java/cn/iocoder/yudao/module/commerce/controller/admin/PointAdminController.java:41 | 旧A/关闭桥接/开发回退 |

## 目录外直接调用补充

| 前端位置 | 方法与路径 | 后端Controller位置 |
|---|---|---|
| pages/account/index.js:73 | POST /infra/file/upload | yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java:46 |
| pages/account/index.js:76 | PUT /platform/profile/mine | platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/controller/admin/StaffProfileController.java:48 |
| pages/account/index.js:90 | PUT /platform/profile/mine | platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/controller/admin/StaffProfileController.java:48 |
| pages/account/index.js:109 | POST /platform/messages/{id}/read | platform/platform-workflow/src/main/java/cn/iocoder/yudao/module/platform/workflow/controller/admin/PlatformMessageController.java:49 |
| pages/account/index.js:114 | GET /platform/messages | platform/platform-workflow/src/main/java/cn/iocoder/yudao/module/platform/workflow/controller/admin/PlatformMessageController.java:35 |
| pages/account/index.js:114 | GET /platform/messages/unread-count | platform/platform-workflow/src/main/java/cn/iocoder/yudao/module/platform/workflow/controller/admin/PlatformMessageController.java:43 |
| pages/account/index.js:116 | GET /platform/profile/mine | platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/controller/admin/StaffProfileController.java:42 |
| pages/account/index.js:117 | POST /platform/messages/read-all | platform/platform-workflow/src/main/java/cn/iocoder/yudao/module/platform/workflow/controller/admin/PlatformMessageController.java:55 |
| pages/profile/profile-page.js:84 | GET /platform/profile/mine | platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/controller/admin/StaffProfileController.java:42 |
| pages/profile/profile-page.js:92 | GET /platform/messages/unread-count | platform/platform-workflow/src/main/java/cn/iocoder/yudao/module/platform/workflow/controller/admin/PlatformMessageController.java:43 |
| pages/admin/owner-page.js:40 | GET /platform/profile/mine | platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/controller/admin/StaffProfileController.java:42 |
| pages/workflow/quote-export/index.js:13 | GET /franchise/sales/quotes/{quoteId}/export-sheet | domains/franchise/franchise-sales/src/main/java/cn/iocoder/yudao/module/franchise/sales/controller/admin/SalesController.java:127 |
| pages/workflow/explore/platform-bridge.js:437 | POST /app-api/design/v1/assets/{assetId}/content | 当前拼接错误，无对应独立B路由 |

另：api/finance.js:149直接wx.request的开发上传回退与api/engineering.js:191同路由；api/finance.js:132、api/engineering.js:181、pages/workflow/explore/platform-bridge.js:431是票据返回的外部uploadUrl原始PUT，不计固定后端路由。

## 检查与限制

- 只读静态提取并人工复核特殊路径、条件Bean、B启动依赖、嵌套运行jar class、核心契约与错误映射；未修改仓库、提交、推送、重启或发起业务写入。
- 不声称完整字段契约、全部业务Service分支、全部岗位权限、真实数据库和外部渠道已通过；匹配Controller是实现存在的证据，动态可用性仍需实际页面/请求/服务端日志。
- 已检查部分“stub/占位”线索：结算controller的旧注释仍写费用0占位，但实际EngClosureService.java:276-286已聚合expenses，不能凭过期注释报告功能缺失；微信/SMS stub和内容fake是受profile约束的显式测试provider，不能等同于生产实现缺失。

## 修复后的差异说明

- 视频首尾帧/参考素材已补原始上传与assetId关联；财务、工程及设计上传不再使用旧A开发回退，真实B通道仍待验收。
- 个人充值/记录两个路由已退役，不再调用旧个人钱包；公司积分接口当前api2仍404，不能算已上线。
- 作品、发布记录及获客统计已对齐真实内容API；首发采用手动发布登记，定时与伪授权账号已移除。
- 业务首页汇总已补分页；账户、工作流、财务及岗位门禁的修复结果分别见最新报告。设计参考图跨应用参与生成仍缺协议。
