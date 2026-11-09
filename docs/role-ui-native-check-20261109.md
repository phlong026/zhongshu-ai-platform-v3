# 多岗位页面与身份配置复核记录

日期：2026-11-09。小程序源码：`fix/profile-navigation-20261109` / `57859f7c315d8478f99379996c4d473de8260608`。本次按“先确认 Web 身份配置，再打开微信开发者工具测试”的顺序执行。

## 结论

**其他岗位页面异常尚未全部修复。** 已修复并回归的是原生“我的”页的循环跳转、姓名/岗位初始化与底栏选中状态；这不代表工作台、资料/消息/身份子页和所有业务跳转均通过。

Web 身份配置也未形成完整闭环。后端已有组织、部门、员工、任职与离职交接能力；Web 已有组织开通表单及菜单种子，但当前菜单裁剪会隐藏入口，员工任职配置与业务岗位会话尚未完成。已有方案已评审，结论为需修改后再实施。评审与补充的六项任务、十二项验收场景位于独立 Java/Web 仓库：

`/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/docs/web-admin-design/身份配置Web任务书评审-20261109.md`

评审提交为 `20bc6f0`，未修改业务源码或原方案。两个仓库虽有同名远程地址，Git 历史独立。

## 测试方式与证据边界

- 已启动本机 `/Applications/wechatwebdevtools.app`，使用官方 CLI `auto --project … --auto-port 19430 --trust-project` 连接；窗口保持打开。
- 从小程序已提交源码建立隔离项目 `/private/tmp/zhongshu-role-native.d1kFPr`。仅该临时副本增加测试夹具，未改当前业务源码。
- 使用模拟 API token、任职和姓名；GET 返回按接口核对的空数据。阻断非 GET、上传、下载和 socket，未向真实服务发送业务写请求、未发短信、未创建真实员工。
- 记录并恢复四个会话/账号 storage key，不清空开发者工具全部缓存；每批恢复与断开状态均记录。
- 测试调用登录后的 `goAfterLogin`，不代表真实微信授权、验证码送达或服务端登录已验收。
- 原生自动化存在部分超时与中转页读取时序问题。未取得稳定页面快照的项目列为未验证，不能作为手机卡顿复现、产品失败计数或验收通过。
- 初版工程截图的“待办暂时无法加载”受夹具 DTO 错误影响；夹具后来改为 `{items:[],failedDomains:[],actionableCount:0}`，不将该提示归为产品缺陷。
- 报告中被阻断的 POST 均为页面自动申请 `/platform/wallet/session`；这不能直接判定为越权写入，钱包流程不在本次有效验收范围。

## 已有修复的验证

`npm run check` 本次执行退出码 0：检查 34 个原生页面、177 个 JS、59 个 WXML，并通过全部 23 项导航回归测试。覆盖九种角色的“我的”首次显示、旧入口单向回归、连续往返与重复点击、游客与跳转失败提示。

原生模拟器中已保存老板、业务员、业务负责人、工程人员、财务负责人、设计行政的“我的”截图，姓名、岗位与底栏 index=4 正常。其余组合若自动化未稳定读到页面，不补写为通过。真机切页耗时、长时间操作和网络波动未验收，不能承诺手机已无卡顿。

## 已证实的遗留问题

| 编号 | 影响页面 | 证据与当前行为 | 后续修复验收 |
| --- | --- | --- | --- |
| UI-01／高 | 个人资料、消息、组织与工作身份 | `pages/account/index.js:21` 仍用本地 `A.permitted` / `session.loggedIn` 守卫；有效 API token/任职不足以通过。第24行调用不存在的 `auth.token()`，异常被捕获后把 apiLoggedIn 置为 false | 统一识别真实 API 会话；已登录用户进入子页，无会话仍引导登录；过期与撤岗不放行 |
| UI-02／高 | 工程人员、工程负责人工作台 | `pages/build/home/index.js:15–22` 供给 `{key,label,count}`，`components/stat-grid/index.wxml` 消费 `{filter,label,value}`。原生工程负责人截图可见统计标签而无数字；点击也缺 filter | 空数据保留四张卡并显示 0；有数据正确计数；逐卡跳到对应筛选列表 |
| UI-03／高 | 老板、门店负责人工作台 | 原生 owner 截图保留卡片壳，但本月经营、账本数字及当前业务内容缺失。`pages/admin/owner-page.js:68–80` 未构造模板读取的 desk.home / desk.ledger，平台待办为 idle | 按真实接口完整构造首页模型；零值、加载、失败分别可见，不能用缺字段冒充无数据 |
| UI-04／中 | 工程及设计行政登录后首页 | 工程 tab 的专属页跳转、business 页的设计行政跳转仍只看本地 actor；API-only 会话与底栏专属首页定义不一致。相关源码为 `pages/engineering/index.js:7–8`、`pages/business/index.js:21`、`utils/organization.js` | 以当前有效任职决定登录落点和底栏首页；保留权限守卫；无需再次点首页才能进入专属工作台 |
| UI-05／中 | 财务默认首页/总部财务 | `pages/admin/finance-desk/index.js:14` 默认 view/flowTab 仍依据本地 X.context；API-only 财务会落为 flows/receivables，而非默认 desk；总部口径也需核对 | 依据 API 任职设置默认工作台与总部/门店口径；显式 view/tab 参数仍生效 |
| UI-06／中 | “我的”公司名与管理入口 | `pages/profile/profile-page.js:52–67` 已用 API 岗位显示角色，但公司与管理入口仍由本地 actor 构造。老板截图显示“未选择公司”，管理项不能据此认定齐全 | 用真实任职/能力生成公司和入口；普通岗位不因补入口获得管理权限 |

UI-01 已在本次用真实 account 页面定义和 API 假会话做 Node 行为诊断：九种角色 × 三个入口共 27 例均重现“已有 API 任职仍重定向登录”；同时确认 `typeof auth.token === 'undefined'`。这是确认仍存在的缺陷，不是 27 项业务验收通过。

UI-02 已用真实页面 loadOnline 与真实 listCards 做一条有项目诊断：输入一个 IN_PROGRESS 项目，ALL.count 为1，但在建 count 仍为0；listCards 没有 statusKey，页面却按该字段统计。所有统计项同时缺 value/filter，最近项目也没有 url。本诊断不发送网络请求。

UI-02 还存在同类字段缺口：模板读 `eng.tasks/previewTasks`，页面只设 waiting；最近项目模板读 url/progress/status，页面仅设 name/stage/image。空项目数据不能验证“继续办理”，需要在修复时增加有项目场景。

UI-03 的月份选择与 data-key 跳转也需随首页适配一并验收；本次未执行经营写入、付款、归档或审批。

## 原生结果的读取规则

原生用例遍历九种角色及总部财务、总部财务负责人、总部老板，共十二种启用组合。该数字代表已尝试覆盖，不代表十二个工作台或整条登录业务验收通过。结构化结果和代表截图见 `docs/evidence/20261109-role-native/`。

最终采用当前页原子快照读取，避开失效 pageId；修正夹具后取得以下五类工作台证据，五类均无未识别 GET、无实际写请求：

| 角色 | 稳定到达的页面 | 观察结论 |
| --- | --- | --- |
| 老板 | admin/home | ready，卡片壳可见但经营/账本数字缺失；平台待办 idle；门店负责人补测同类表现 |
| 业务员 | business | ready，业务视图与空待办正常；未验证有客户时的所有业务操作 |
| 工程人员 | build/home | 待办 ready，空项目提示可见；统计四卡缺数字，不能记整页通过 |
| 财务专员 | admin/finance-desk | 数据 ready、台账0行、待办 ready；默认 view=flows，没有进入 desk |
| 设计行政 | workflow/commission?view=admin | 显式进入 admin 后 ready；登录自动落点仍有 UI-04 缺口 |

代表截图：`evidence/20261109-role-native/boss-workbench.png`、`engineer-workbench.png`、`finance-workbench.png`、`boss-profile.png`。老板首次过早截图曾为空白，等待400ms复拍已正常；不能将该自动化首帧当作手机黑屏复现或切页耗时测量。

对老板/财务登录停留 management 中转页的早期结果，不直接记为产品跳转错误：当前 `pages/management/index.js:12–14` 已有 API 任职 reLaunch 分流，须排除自动化时序后再判定。工程与设计行政的静态条件缺口另见 UI-04。

全部批次 storageRestore、disconnect 均成功；最后页面为老板工作台，开发者工具保持打开。原生资料/消息/身份入口未形成稳定断言，UI-01 以主线27例 Node 行为诊断为据。

## 验收与后续顺序

1. 优先修复共同 account 会话守卫，再修老板/工程首页的模型与模板契约，分别用零数据和有数据验收。
2. 修复工程、设计行政、财务的 API 岗位首页与默认视图，逐个测试登录后与底栏首页的一致性。
3. 按 Web 方案评审收口员工任职配置任务；平台全局开通与本组织员工管理必须分开。
4. 在测试环境用真实配置的角色、真实登录和手机复核，单独记录切页耗时、错误日志、后端返回及空数据占位。受控假会话和模拟器不能替代此关。

本次交付为核查、评审和测试证据；未实施以上遗留修复，未上传小程序、未推送远程或部署后端。已有未提交文件保持原样。
