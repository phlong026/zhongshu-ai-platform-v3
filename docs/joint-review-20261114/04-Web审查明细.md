> 历史诊断：本文件针对早期f77fad1c旧主目录，已被用户选定的platform-split取代。其53条依赖审计、页面范围及旧视觉结论不得作为最终候选结论；当前结果见01报告、06依赖报告及07总部专项。

# Web 管理后台上线联合评审（2026-11-14）

基线：`/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台`，分支 `fix/joint-release-audit-20261114`，HEAD `f77fad1c`。范围为 `平台程序/admin-ui`，并只读核对 Java 接口契约。

## 结论

**REQUEST CHANGES / 暂不作为随 B 首发的可放行 Web 制品。** 当前管理员 `/zs` 主体可构建，员工身份/租户/切岗专项测试也通过；但全量测试门禁未绿，员工 Web 首发范围尚未决且若开放则缺少 P1 财务/驾驶舱/待办页面，登录后岗位加载失败和页内切岗失败均会把用户留在错误状态。依赖扫描有 1 critical/26 high；已核验 critical DOMPurify 的特定 SAFE_FOR_TEMPLATES+Shadow DOM 触发方式未在当前 SafeEditor 调用中出现，但补丁升级仍应在发布前完成。

## P1

### WEB-P1-01 员工 Web 首发范围未闭合；若随 B 首发开放，核心页面缺失

- 证据：`admin-ui/src/router/modules/fr.ts:10-31` 的 `/fr` 仅注册“工作身份”和“部门与员工”；`src/api` 只有 `platform/staff.ts`，无 franchise finance/CRM/engineering API。设计基线 `平台程序/docs/web-admin-design/加盟商员工Web管理后台界面设计-20261107.md` 的 P1 要求登录/岗位切换、驾驶舱、财务三页、组织人员、待办；小程序仓库 `docs/evidence/release-20261115/01-scope.md` 仍将“Web 是否随 B 首发”列为待确认。
- 影响：若对外宣称三端一并上线，finance/boss 等员工登录后只能选岗/管人，无法执行财务对账、经营查询、统一待办，交付与宣传口径不一致。
- 最小修复：先将首发裁定落文档；若首发不含 Web，发布入口、部署清单和 UAT 明确排除 `/fr` 业务门户；若包含 Web，至少交付设计基线 P1 的驾驶舱、财务三页和待办，统一复用当前 `X-Post-Id`/generation 机制。
- 验收：boss/owner/finance/finance-lead 四类岗位各用真实授权账号登录；菜单与后端权限码一致；驾驶舱、财务登记/对账/更正、待办办理均覆盖成功、空、失败重试、403、切岗迟到响应；未经权限岗位页面不可见且接口 403。

### WEB-P1-02 登录成功后岗位列表加载失败会把有效会话困在验证码页

- 触发：`staffSmsLogin` 成功后，`identity.loadPosts()` 因网络/502/超时失败。
- 证据：`src/views/fr/platform-login/index.vue:95-107` 已在第 101-102 行写入 token 与 staff portal，再 await `loadPosts`；页面只渲染 `notice`（模板第 9 行），不渲染 `identity.error`，且 `posts` 为空时继续显示短信表单。`loadPosts` 会设置 `identity.error`（`src/store/modules/platformIdentity.ts:33-40`），但此页无重试按钮。
- 影响：验证码通常已消费，用户看见的是新的登录表单；再次提交可能失败，实际有效 token 又已存在，形成假登录失败和客服工单。
- 最小修复：区分“未认证”和“已认证待加载岗位”；staff token 已落地时显示 `identity.error` 与“重新加载岗位”按钮，重试只调用 `loadPosts`，不得重复短信登录；必要时提供显式退出重来。
- 验收：模拟 login 200、posts 502；断言 token 保留、验证码表单不出现、错误与重试可见；重试 posts 200 后单岗自动进入、双岗显示选择；401 时清会话回短信表单。

### WEB-P1-03 组织人员页切岗失败后清空上下文并误报“无权限”，无恢复入口

- 触发：用户在“部门与员工”顶栏切换岗位，`GET /platform/posts/current-context` 超时/失败。
- 证据：`src/views/fr/org-staff/index.vue:381-393`（当前函数附近）先 `resetTransientState` 并清空四组数据，再直接 await `identity.selectPost`，无 catch；`selectPost` 在 `src/store/modules/platformIdentity.ts:43-67` 已执行 `beginPostSwitch` 和 `context=null`，失败仅 finally，不设置错误。结果页 `canQuery=false`，模板第 24 行显示“当前工作身份没有组织人员查看权限”。
- 影响：网络问题被误报为权限问题，页面没有重试/回滚；未捕获 Promise 还会进入全局错误路径。
- 最小修复：`selectPost` 失败时设置可恢复错误；页面 catch 后跳工作身份页或展示重试，保留/恢复上一个已验证岗位上下文，只有服务端确认新岗位后再清业务数据和标签页。
- 验收：A 岗数据已加载→切 B 岗上下文 502；A 岗数据不被 B 响应污染，页面显示网络失败而非无权限，可重试或回 A；重试成功后才清 A 数据并加载 B；旧请求迟到仍被 generation 丢弃。

### WEB-P1-04 新建部门在超时重试下会重复创建同名部门

- 证据：`src/api/platform/staff.ts:84-85` 的 `POST /platform/org/teams` 无 `Idempotency-Key`；页面 `src/views/fr/org-staff/index.vue:432-438,497-520` 防同一时刻双击，但超时后明确提示重试且复用的是无键请求。后端 `StaffOrgAdminController.java:135-142` 直接调用 `OrgService.createTeam`；`OrgServiceImpl.java:49-62` 每次建新 system_dept；`V20261022.702__pf_org.sql:33-50` 无 `(org_id,name)` 唯一约束。
- 影响：服务端已提交但响应丢失时，用户重试生成两个同名部门，后续岗位和数据范围归属产生歧义。
- 最小修复：按现有预算写操作模式为创建部门生成稳定幂等键，同一表单内容重试复用；服务端在事务内按幂等键回放，或定义并锁定组织内部门业务唯一键。仅靠按钮 loading 不足以解决未知终态。
- 验收：同键串行/并发两次请求只产生一个 deptId 且响应相同；第一次提交成功但客户端超时后重试仍返回原 deptId；改名后生成新键并创建新部门；跨组织同名仍按业务规则允许。

### WEB-P1-05 生产依赖存在 critical/high 公告，需补丁升级后重跑

- 证据：`pnpm audit --json`（全依赖扫描，不能全计为生产漏洞） 产物 `/tmp/zs-review-admin-pnpm-audit.json` 为 53 advisories（1 critical、26 high）。直接运行时依赖包括 `dompurify@3.4.16`（critical+high，补丁 3.4.17）、`markdown-it@14.3.1`（high，补丁 14.3.3）、`vue-i18n@11.4.0`（high，补丁 11.4.14）。`SafeEditor.vue:13-17` 当前使用字符串输出、`USE_PROFILES`，未使用 critical 公告要求的 `SAFE_FOR_TEMPLATES`、DOM 输出或 attached shadow root；因此本轮没有把该公告宣称为已可利用线上 XSS。
- 影响：发布供应链门禁不满足；未来配置变化可能使当前低可达公告转为可达，且直接依赖已有无破坏补丁版本。
- 最小修复：最小升级上述直接依赖到修复版并更新 lockfile；不执行大版本迁移。对 SafeEditor 和 MarkdownView 做恶意 HTML/复杂 Markdown 回归，核对产物仍只包含预期依赖。
- 验收：prod audit 不再报告上述直接依赖 critical/high；`form-editor-security.test.js`、完整 Node tests、typecheck、prod build 通过；恶意 SVG/template/shadow-root 用例不产生活跃事件处理器。

## P2

### WEB-P2-01 当前提交的自动化门禁不全绿

- `node --test tests/*.test.js`：135/136；失败 `tests/project-navigation.test.js:128`，预期未包含新 `/zs/org-point-pricing`，实际菜单已包含。属于新增 D8 页面后测试未同步，而非页面构建失败。
- `pnpm lint:style:check`：11 errors，涉及预算、公司池价格、公司开通等 4 个页面。
- `pnpm lint:format:check`：2 files fail（budget/index.vue、platform-org/index.vue）。
- `pnpm lint:eslint:check`：0 error、1 warning（SafeEditor 组件名）。
- 修复与验收：更新真实菜单期望并保持权限拒绝断言；只格式化列出的本任务文件；四项检查全部退出 0。

### WEB-P2-02 组织列表加载是全量返回+客户端分页，容量继续扩大时需服务端分页

- 证据：`StaffOrgAdminController.java:147-160` 全量 `listOrgPosts` 后批量查用户；`org-staff/index.vue:270-276`（computed 区）只在浏览器 `slice` 分页。
- 影响：在当前 200 组织/1000 员工目标、员工分散时可接受；若单组织任职接近千级或继续增长，接口载荷、DOM 状态和每次写后全量刷新线性增长。
- 建议：当前容量做 1000 任职组织的 p95 实测后裁定；超过目标阈值时改服务端分页/筛选，写后只刷新当前页与摘要。
- 验收：目标数据量下列表首屏与切页 p95、服务端查询时间和响应大小有记录；无 N+1（当前 usersByIds 为批量）；阈值不达标再实施分页。

### WEB-P2-03 表单设计器执行 `parseFunc` 字符串代码，需限制为可信管理员工具

- 证据：`src/components/FormCreate/src/components/useApiSelect.tsx:186-191` 使用 `new Function`；`selectRule.ts:150-165` 允许用户录入代码；入口为 `src/views/infra/build/index.vue` 的表单设计器。
- 影响：当前更像高权限用户自执行代码，未找到面向加盟员工的调用或服务端持久化链，不能直接宣称远程代码执行；但若未来允许导入/共享表单 JSON，恶意 parseFunc 可在查看者浏览器以其管理员会话执行。
- 建议：发布菜单仅授予可信平台管理员；后续用字段映射/JSONPath 白名单替代任意 JS，或对导入规则拒绝 parseFunc。增加“非可信规则不得执行”的测试。

## 验证证据

- `pnpm ts:check`：PASS。
- `pnpm build:prod`：PASS，Vite 3729 modules，19.35s。
- 定向身份/租户/分页：36/36 PASS。
- 全量 Node：135 PASS / 1 FAIL。
- ESLint：0 error / 1 warning；Stylelint 11 error；Prettier 2 files fail。
- 未做：真实短信发送、真实员工账号登录、生产 API、浏览器四岗位 UAT、200 在线/100 AI 并发（这些不是本地 build 可证明的）。主代理已在本地浏览器核对 5174 管理员→员工登录与空表单错误态，未发短信。

## 推荐开发顺序

1. 先裁定 Web 是否进入 B 首发；若进入，WEB-P1-01 是范围主线。
2. 并行修 WEB-P1-02/03（认证与切岗恢复）和 WEB-P1-04（部门幂等），各先补失败行为测试。
3. 独立依赖升级处理 WEB-P1-05，避免与业务修复混合。
4. 收尾修 WEB-P2-01，使全量测试、typecheck、build、lint 全绿。
5. 用真实四岗位账号做 Web UAT；容量并发由后端/联调通道统一压测，Web 只记录首屏与请求指标。
