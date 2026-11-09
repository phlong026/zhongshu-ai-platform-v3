# 登录、工作台与身份配置续测

本轮继续验证小程序原生跳转及 Web 身份配置，实际修复了登录/切岗连续导航超时，以及 Web 撤岗缓存、无任职提示、过期登录入口三个异常。

## 小程序发现与修复

原生探针实际记录到：`account.goAfterLogin` 先 `switchTab('/pages/management/index')`，中转页 `onShow` 又调用 `reLaunch('/pages/admin/home/index')`，路由停留中转页，前一个 switchTab 最终返回 `switchTab:fail timeout`。当时令牌、当前岗位和任职缓存均存在；相同岗位直接打开同一个老板工作台成功。底层运行时原因尚未单独归因。

修改 `miniprogram/pages/account/index.js`：登录与主动切岗共同调用 `openApiHome`，从现有 `organization.navigation(...).tabs[0]` 取得真实岗位首页；原生 Tab 使用 switchTab，专属工作台一次 reLaunch 直达，并在导航失败时提示重试。删除两个入口对中转 Tab 的依赖，不再复制一套路由映射，也没有新增依赖。

`scripts/verify-api-role-pages.cjs` 增加 12 组合 × 登录/切岗两条路径共 24 条行为断言。修复前老板用例明确失败，修复后全部通过。

## 微信开发者工具复测

官方 CLI/automator 编译隔离副本，使用受控有效任职调用真实 `account.goAfterLogin`，最终路由与页面栈直接观察。**12/12 已开放岗位与组织组合通过**：

| 组合 | 最终落点 |
| --- | --- |
| 门店老板、门店负责人、总部老板 | `/pages/admin/home/index` |
| 门店业务员、业务负责人 | `/pages/business/index` |
| 门店工程人员、工程负责人 | `/pages/build/home/index` |
| 门店及总部的财务专员、财务负责人 | `/pages/admin/finance-desk/index`，view=desk |
| 总部设计行政 | `/pages/workflow/commission/index?view=admin` |

老板热重编首例在触发前尚未进入账号页，保留首次结果后进行了一次有限稳定化重试，最终通过。没有把该工具准备阶段误报为产品失败，也没有删除首次证据。

另验证老板甲门店 → 工程乙门店：真实 `selectApiPost` 方法直达工程工作台，再由有界测试命令打开“我的”，角色与公司同步正确；此项不作为工程底栏点击已通过的证据。

原生来源为 `1bba6c5` 加账号页单文件修复，SHA-256 为 `cafc340426e2ecdd96b954827f8b507c58d1c1a45ce9b38e8145057f57143631`。与当前 `606aecc` 工作树逐文件比较，177 个业务 JS 完全一致；隔离 app.js 仅增加测试夹具加载。当前标题样式变更未纳入新视觉验收。

真实 HTTP、上传及 Socket 受测试夹具阻断，四个缓存键各阶段均恢复成功。测试未发送真实短信、执行微信手机号授权或写生产业务。自动化连接已结束，微信开发者工具窗口保持打开。

## 连续切页的证据边界

修复前老板、业务员各完成 10 轮往返及当前“我的”重复点击，最终角色、组织与页面栈正确。工程、财务、设计的重复连切遇到 Element.tap 超时，仍是工具未验证项。自动化记录混入异常 SDK 等待，**所有此类耗时仅作诊断，不作为性能达标证据**。

因此，本轮能确认登录落点和切岗上下文修复通过；手机“我的”黑屏/长时间卡顿、全角色连续底栏交互仍需开发版真机验收。

## 本地检查与复核

- `npm run check`：项目既有 JS/WXML/JSON/资源与包体检查通过；“我的”导航 23 项、认证会话 54 项、成员操作隔离 10 项通过，并包含新增 24 条直达断言及既有工作台、材料、组织与交接回归。
- 双通道登录契约通过；两个修改 JS 文件 `node --check`、差异空白检查通过。沿用项目格式，没有对既有聚合文件整体重排。
- 独立代码复核未发现新增阻断问题；游客入口、候选岗位约束及原有会话隔离保留。
- 两个修改文件凭证模式扫描 0 命中，小程序生产依赖审计 0 项漏洞；没有依赖变更或数据库迁移。

证据目录：`docs/evidence/20261109-continued-testing/`。

- `native-account-direct-routing-report.json`：修复后最终 12 组合、切岗与缓存恢复结果。
- `patched-landing-first-pass.json`、`patched-landing-boss-retry.json`：首次结果和唯一稳定化重试。
- `boss-routing-probe.json`：原始中转导航调用、回调及同目标直接跳转对照。
- `native-extended-final-report.json`、`raw-landing-report.json`：修复前原始观察；其中重复切页数据不用于性能结论。
- `source-provenance.json`、`artifact-sha256.json`：源码来源及产物校验；两张“我的”截图仅核对角色/组织信息，不代表最新标题样式的视觉验收。

## Web 与后端结果

独立 Java/Web 仓库已提交 `ffaecf7`（真实数据库身份流程测试）及 `0a77324`（三个 Web 异常修复与浏览器证据）：前端 136 项、组织授权 16 类/103 项通过，类型、目标 lint、格式与构建通过。

浏览器使用本地 HTTP 夹具；数据库流程使用真实 PostgreSQL 与组织任职服务，账号角色目录为替身，standalone MockMvc 不验证完整 Method Security。Web 依赖审计仍有 28 项既有告警，本轮未变更依赖。

完整说明：[/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/docs/web-admin-design/身份配置续测-20261109.md](/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/docs/web-admin-design/身份配置续测-20261109.md)。

用户已有未提交文件保留。本轮仅本地修复、测试和提交，未推送远程、上传小程序开发版或部署后端；可通过反向回退本轮逻辑提交撤销源码变更。
