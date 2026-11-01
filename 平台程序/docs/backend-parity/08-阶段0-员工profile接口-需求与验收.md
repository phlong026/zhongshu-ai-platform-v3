# 阶段 0 · 基-3 员工 profile 接口需求文档与验收标准

> 批次：后端化工程 · 阶段 0 · 基-3　日期：2026-11-01　状态：V4 定稿（Codex 评审循环：R1 7P1+2P2 / R2 2P1+3P2 / R3 0P1+3P2 全部闭环，R3 判 PASS）
> 流程：需求文档 → Codex 评审循环至 PASS → 后端开发 → e2e 全绿 → 提交推送（07 文档 §三 节拍）。
> 上游：07 移交文档 §二（基-3 = 4.2 后端：姓名/头像/手机号读取与更新，规模=最小）＋ 00 文档 §4.2。
> 范围：**仅 monorepo 后端**（platform-org 模块）；v3 小程序 profile 页接入随本批另段推进（双仓库纪律）。

## 0、修订记录

**V4（2026-11-01，吸收 Codex R3 全部 3 P2，R3 判 PASS）要点**：
1. R3-01 请求类型契约：`nickname` 必须为 String（非 String → 1_101_000_011）；`avatar` 仅允许缺省/null/String（其他类型 → 1_101_000_012），杜绝强转进通用异常返回非模块码；AC3/AC4 各补类型错误用例。
2. R3-02 测试边界澄清：`StaffProfileServiceTest` 为**纯 Fake 单测**（PlatformPgIntegrationTest 仅提供平台迁移骨架，不验证 system_users 真实写入）；AC4/AC9 的真 PG 证据由 e2e 提供。
3. R3-03 错误码登记回写 07 §五.3：1_101_000_011/012（本批基-3）。

**V3（2026-11-01，吸收 Codex R2 全部 2 P1 + 3 P2）要点**：
1. R2-01 F12/AC8 重绑条件勘误：有效绑定存在时登录**直接发令牌、不读 phoneCode**；带 phoneCode 建新绑定仅发生在绑定失效或不存在时。AC8 不带 phoneCode 的正确理由=**防止旧绑定失效后被重绑掩盖**（失效绑定会被 revoke 后走重绑分支，带 phoneCode 即可掩盖失效）。
2. R2-02 昵称归一化定稿：**先 trim，校验、保存、响应均用 trim 后值**（底座原样保存、列 varchar(30)：仅按 trim 长度校验而保存原值会让「31 空格+张」过校验却落库失败）；AC3 补首尾空格用例。
3. R2-03 手机号只读承诺范围限定为 `/platform/profile/**`；旧入口 `PUT /system/user/profile/update` 仍接受本人 mobile 更新（既有读取路径为 `/system/user/profile/get`），旧入口治理列 §九① 遗留，不宣称全后端收敛。
4. R2-04 幂等口径精确化：**profile 业务字段幂等**（允许 `update_time` 审计刷新）；同值顺序重放不重复发布资料变更事件（底座同值判断已有）；不另建幂等体系。
5. R2-05 AC8 注明 `provider=stub`（`stub-<loginCode>` 固定 openid 映射是替身特性；真实微信应换新 loginCode 解析同一 openid）。

**V2（2026-11-01，吸收 Codex R1 全部 7 P1 + 2 P2）要点**：

**V2（2026-11-01，吸收 Codex R1 全部 7 P1 + 2 P2）要点**：
1. R01 avatar 语义定稿为唯一规则（**键不存在=保持；显式 null 或空串=清除（落库空串，响应统一映射 null）；URL=更新**），并写明底座 `updateById` 在 MP NOT_NULL 策略下传 null 不落库的约束；AC4 补真 PG 四态断言（缺省/显式 null/空串/URL 各自的落库与读回）。
2. R02 断言口径纠偏：yudao 未认证返回 **HTTP 200 + body.code=401**（`AuthenticationEntryPointImpl` 写 CommonResult 不设状态码），成功 `(200,0)`、业务校验失败 `(200,业务码)`；校验走**业务异常（ServiceException+ErrorCode）**，不用 Bean Validation（避免通用 400）。e2e 脚本须显式保存并断言 HTTP 状态。
3. R03 AC8 重写：微信登录带 phoneCode 会作废旧绑定后重新绑定，不能证明旧链路完好——改为**绑定→更新资料→同身份不带 phoneCode 再登录→断言 `needPhone=false`、userId 不变、绑定行 `mobile_hash` 不变**（查库）。
4. R04 控制器包路径必须是 `**.controller.admin.**` 才会自动挂 `/admin-api` 前缀（`YudaoWebAutoConfiguration` 按包匹配）——定稿 `cn.iocoder.yudao.module.platform.org.controller.admin`。
5. R05 事实纠偏：现有改号处理是「作废绑定+吊销令牌」端口（`StaffWechatBindingServiceImpl.revokeAllForUser`），**不是**改写 mobile_hash；且成员管理控制器（StaffOrgAdminController）未接线改号、底座 updateUser 亦不串动作废流程——§九① 改为「换号=待建能力」，删除「管理员通道现成」表述。
6. R06 头像 URL 增加长度上限 **≤512**（PG 基线 `system_users.avatar` 为 varchar(512)，超长合法 URL 会落库失败）；AC4 补 512/513 边界与失败后资料不变断言。
7. R07 迁移号事实纠偏：**pf 7xx 的 704 已被 `V20261022.704__pf_wechat_binding.sql` 占用**——本批无迁移不变；基-2 续号改为 705（实施前复核），并同步回写 07 文档 §二/§五.4。
8. R08 背景修正：底座已有 `/system/user/profile` GET/PUT（PUT 含 mobile），缺的是**平台员工收敛契约**（白名单 nickname/avatar、mobile 只读）；适配层只构造白名单字段，不透传客户端原始 Map。
9. R09 D-基3-1 措辞修正：URL 契约是对 07 §四推荐默认（文字资料先行）的**有意偏差**（理由：avatar 本就是 URL 列、与基-1 上传通道兼容），已注明；assetId 化属未来另一次契约变更。

**V1（2026-11-01）**：初稿。

## 一、背景与目标

员工端「我的资料」（v3 `pages/account/index?view=profile`）当前为纯本地态：姓名存本地 storage、头像 `wx.saveFile` 存本机路径、手机号只读打码展示。登录双通道（微信/短信）已上线。

底座已有本人资料接口 `GET/PUT /system/user/profile`（PUT 含 email/mobile/sex/avatar 全字段更新），但那是 C 端/管理端通用入口：字段面过宽（含 mobile/email 自助修改）、无平台员工收敛口径。本项提供**平台员工专用收敛契约** `/platform/profile/**`：白名单 nickname/avatar 读写、mobile 只读；适配层只构造白名单字段，不透传客户端原始请求体。规模=最小（07 §二），是阶段 0 的节拍起点。

## 二、现状勘察结论（已核实，开发遵照）

| # | 事实 | 出处 |
|---|---|---|
| F1 | 员工账号即底座 `AdminUserDO`：nickname/mobile/avatar/deptId/status 字段齐备；username=手机号，无密码通道 | `yudao-module-system/.../user/AdminUserDO.java` |
| F2 | 底座 `AdminUserService.updateUserProfile(id, UserProfileUpdateReqVO)` 已含：用户存在校验、email/mobile 唯一性校验、昵称/头像变更事件发布；**但更新走 `updateById`（MP 默认 NOT_NULL 策略）：字段传 null 不落库，无法借此清列**；VO 校验 nickname ≤30、mobile 11 位、avatar @URL（仅 controller @Valid 生效，service 直调不触发） | `AdminUserServiceImpl:196-207` |
| F3 | **手机号是登录凭证**：短信登录按 mobile 找人；微信登录按 `hashMobile(system_users.mobile) == pf_wechat_binding.mobile_hash` 判「手机号未变」，不一致作废绑定；身份模块已有改号处理端口 `revokeAllForUser`（作废绑定+吊销令牌），但成员管理控制器未接线改号、底座 updateUser 亦不串动作废流程——**自助换号属待建能力** | `StaffWechatLoginServiceImpl:48-91`、`StaffWechatBindingServiceImpl:66-80`、`StaffOrgAdminController`（无改号操作） |
| F4 | v3 蓝本编辑页**仅开放姓名编辑**（1–20 字校验），头像本地保存，手机号只读打码 | v3 `pages/account/index.js`（saveProfile/avatar/maskedPhone） |
| F5 | 「登录即可访问、不挂菜单权限、不要求 X-Post-Id」的平台接口先例：`GET /platform/posts/mine` | `MyPostController`（platform-authz） |
| F6 | platform-org 对底座 system 的依赖走接口解耦（`SystemDirectory` + `YudaoSystemDirectory`，测试用内存 Fake 替换） | platform-org 包内先例 |
| F7 | 平台错误码段位：1_101 org / 1_102 workflow / 1_103 authz / 1_104 identity；org 段已用至 1_101_000_010 | `PlatformOrgErrorCodes` 等 |
| F8 | 无需新表、无迁移：全部复用 `system_users` 既有列。**pf 7xx 的 704 已被 `V20261022.704__pf_wechat_binding.sql` 占用**（07 文档「下一个 704」为笔误，已回写修正）；基-2 续号预计 705、实施前复核 | `platform-identity/.../db/migration/pf/`、`application-pg.yaml` |
| F9 | **未认证响应形态：HTTP 200 + body.code=401**（`AuthenticationEntryPointImpl` 经 `ServletUtils.writeJSON` 写 CommonResult，不设 HTTP 状态码）；业务校验失败=HTTP 200 + body.code=业务码 | `yudao-spring-boot-starter-security/.../AuthenticationEntryPointImpl`、`GlobalExceptionHandler` |
| F10 | 控制器须落在 `**.controller.admin.**` 包下才会自动挂 `/admin-api` 前缀（`YudaoWebAutoConfiguration` 按包匹配） | `yudao-spring-boot-starter-web/.../WebProperties.java:24` |
| F11 | PG 基线 `system_users.avatar` 列为 **varchar(512)**，超长 URL 落库失败 | `sql/postgresql/ruoyi-vue-pro.sql:5627` |
| F12 | 微信登录 `POST /platform/auth/wechat-login`：openid 已有**有效绑定→直接发令牌（不读 phoneCode）**；绑定失效或不存在时，携带 phoneCode 才进入手机号授权并建立新绑定（失效绑定先被 revoke）。本地替身 `StubWechatStaffClient` 以 `stub-<loginCode>` 固定 openid 映射（provider=stub 特性） | `StaffWechatLoginServiceImpl.login`、`StubWechatStaffClient:25` |

## 三、关键设计决策

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| D-基3-1 | 头像存储（07 §四） | **avatar=URL 引用**（`system_users.avatar` 本就是 URL 列）；本批接口直接收/发 URL，**上传通道依赖基-1**，就绪后接口零改动。**对 07 §四推荐默认（文字资料先行、头像列位留空）的有意偏差**：avatar 列既存且为 URL 语义，开放 URL 读写无新表无迁移；若未来基-1 定为 assetId 引用制，属另一次契约变更 | 上传通道接入时接口不变；assetId 化另行评估（§九②） |
| D-基3-2 | 手机号是否开放本人更新 | **只读**（GET 返回，PUT 不接受；请求体不含该字段，多余字段被忽略）。承诺范围仅限本接口 `/platform/profile/**`：底座旧入口 `PUT /system/user/profile/update` 仍接受本人 mobile，治理列遗留（§九①） | F3：换号是待建能力（账号更新+旧绑定作废+令牌吊销+重新授权），非本批范围；F4：v3 蓝本本就只读 |
| D-基3-3 | 控制器归属模块 | **platform-org**（staff 数据域），错误码顺延 1_101 段；包路径 `cn.iocoder.yudao.module.platform.org.controller.admin`（F10） | 内聚：staff 资料是 org 域数据，未来「管理员改员工资料」同域；authz 只放鉴权类（/platform/posts） |
| D-基3-4 | 对底座依赖方式 | 仿 F6 先例：org 模块内定义 `StaffProfileStore` 接口 + `YudaoStaffProfileStore` 实现（调 `AdminUserService`），单测用内存 Fake | 复用既有解耦模式，单测不拉起 system 全家 |
| D-基3-5 | 权限 | 登录即可（不挂菜单权限、不要求 X-Post-Id），只操作本人数据 | F5 先例；本人资料无组织 scope |
| D-基3-6 | 昵称长度口径 | **trim 后 1–20 字**（严于底座 30） | 与 v3 saveProfile 校验一致（F4），两端同一口径 |
| D-基3-7 | 校验方式 | 全部校验在 `StaffProfileService` 内做并抛**业务异常**（`ServiceException` + 本模块 ErrorCode），不用 Bean Validation 注解 | F9：Bean Validation 失败返回通用 400，无法给出 1_101_000_011/012 业务码 |

## 四、REST 接口契约（`/admin-api/platform/profile/**`）

响应统一为 `CommonResult<Profile>` 包装。断言口径（F9）：成功 `(HTTP 200, body.code=0)`；未认证 `(HTTP 200, body.code=401)`；业务校验失败 `(HTTP 200, body.code=业务码)`。e2e 脚本显式保存 HTTP 状态并断言二元组。

### GET `/platform/profile/mine`

登录态返回本人资料；未认证 `(200, 401)`。

```json
{ "id": 123, "nickname": "张业务", "mobile": "13800002688", "avatar": "https://.../a.jpg" }
```

- `id`：userId（long）
- `nickname`：姓名/昵称
- `mobile`：**完整返回**（打码由前端做，与 v3 maskedPhone 惯例一致）
- `avatar`：URL；**落库空串/NULL 统一映射为响应 null**（未设置语义）

### PUT `/platform/profile/mine`

```json
{ "nickname": "新姓名", "avatar": "https://.../a.jpg" }
```

**avatar 键语义（唯一规则，F2 约束下的定稿）**：

| 请求形态 | 语义 | 落库 |
|---|---|---|
| 键不存在 | 保持不变 | 不写 avatar 列 |
| `"avatar": null` | 清除 | 存空串 `''`（F2：updateById 传 null 不落库，清除必须经空串） |
| `"avatar": ""` | 清除 | 存空串 `''` |
| `"avatar": "<URL>"` | 更新 | 存该 URL |

实现按「控制器接收 `Map<String,Object>` 判键（`containsKey` 区分缺省与显式 null）→ 白名单构造底座更新」处理，不透传原始请求体。

**校验（service 层业务异常，D-基3-7）**：

| 字段 | 规则 | 失败错误码 |
|---|---|---|
| nickname | **类型必须为 String**；必填，**先 trim**（校验、保存、响应均用 trim 后值），trim 后长度 1–20 | 1_101_000_011（姓名须为 1-20 个字） |
| avatar | **仅允许缺省/null/String**；空串或显式 null=清除；非空 String 须为 `http://` 或 `https://` 开头的合法 URL，且**长度 ≤512**（F11 varchar(512)，超长落库失败） | 1_101_000_012（头像地址格式不正确） |

类型契约目的：杜绝 `nickname: 123`、`avatar: {}` 之类输入经强转落入通用异常（HTTP 200 + 非模块错误码）——类型不符直接给模块业务码。

- 幂等：**profile 业务字段幂等**——同 body 顺序重放读回一致；允许 `update_time` 审计字段刷新（底座填充器行为）；同值重放不重复发布资料变更事件（底座同值判断已有，`AdminUserServiceImpl:212`）。不另建幂等体系。
- 响应：更新后的完整资料（同 GET 结构，avatar 经空串→null 映射）。
- 校验失败时资料保持不变（先 trim/校验后落库）。

## 五、错误码（org 段顺延，登记 07 §五.3）

| 码 | 常量 | 文案 |
|---|---|---|
| 1_101_000_011 | PROFILE_NICKNAME_INVALID | 姓名须为 1-20 个字 |
| 1_101_000_012 | PROFILE_AVATAR_INVALID | 头像地址格式不正确 |

## 六、涉及文件

| 文件 | 动作 |
|---|---|
| `platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/controller/admin/StaffProfileController.java` | 新增（GET/PUT /platform/profile/mine；包路径须为 controller.admin，F10） |
| `platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/profile/StaffProfileService.java` | 新增（校验业务异常 + 编排 + avatar 键语义处理） |
| `platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/profile/StaffProfileStore.java` | 新增（接口：findByUserId/updateProfile） |
| `platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/profile/YudaoStaffProfileStore.java` | 新增（实现：调 AdminUserService，白名单构造更新参数） |
| `platform/platform-org/src/main/java/cn/iocoder/yudao/module/platform/org/enums/PlatformOrgErrorCodes.java` | +2 码 |
| `platform/platform-org/src/test/java/cn/iocoder/yudao/module/platform/org/StaffProfileServiceTest.java` | 新增（**无 Spring 纯 Fake JUnit**：new Service + ReflectionTestUtils 注入 Fake store，不依赖 PlatformPgIntegrationTest——本机 Testcontainers 集成测试环境不可用（07 §六/记忆已登记），system_users 真实写入由 e2e 验证） |
| `script/platform-profile-e2e.py` | 新增（仿 franchise-design-e2e 骨架） |
| `docs/backend-parity/08-阶段0-员工profile接口-需求与验收.md` | 本文件 |

## 七、验收标准（可执行）

> e2e 前提：server 以 local,pg,zsdev 运行；dev-setup 传唯一 mobilePrefix（07 §六）。
> 断言口径：HTTP 状态 + body.code 二元组（07 §三.4）。

| # | 断言 |
|---|---|
| AC1 | `mvn clean package -DskipTests` 全量编译通过；迁移清单无新增（F8） |
| AC2 | 登录员工 `GET /platform/profile/mine` → `(200, 0)` 返回本人 id/nickname/mobile/avatar 四字段且与底座一致；**未认证（无 token）→ `(200, 401)`**（F9，GET/PUT 均断言）。越权面=无（接口只操作登录者本人、无组织 scope），故本项以未认证覆盖、无 403 断言——此为设计结论非遗漏 |
| AC3 | PUT 昵称：1 字、20 字边界成功 `(200,0)` 且 GET 读回一致（**trim 归一化**：传 `" 张业务 "` 落库并读回 `"张业务"`）；21 字、空串、纯空格、缺字段、**类型错误（`nickname: 123`）** → `(200, 1_101_000_011)`，且 GET 确认资料未被本次失败请求改动 |
| AC4 | PUT 头像四态（真 PG 落库断言，R01）：URL 更新成功读回同值；显式 `null` 清除后 GET avatar=null 且查库 `avatar=''`；空串清除同效；**键缺省时查库 avatar 列不变**。非法输入 → `(200, 1_101_000_012)`：`javascript:alert(1)`、`ftp://x`、**长度 513 的合法 URL**、**类型错误（`avatar: {}`）**；**长度恰 512 的 URL 成功**（F11 边界）；失败后 GET 资料不变 |
| AC5 | 幂等重放：同 body 连续 PUT 两次，第二次成功且 GET 结果与首次一致 |
| AC6 | 隔离：员工 A 更新后，员工 B GET 仍见 B 本人资料；A 的变更不污染 B |
| AC7 | 手机号不变性：PUT（body 额外携带 `mobile` 字段）→ `(200,0)` 且 GET mobile 与更新前相同——**传入被忽略**（D-基3-2） |
| AC8 | 微信登录回归（**provider=stub**，F12）：测试账号先完成微信绑定（带 phoneCode 首登）→ PUT 改昵称+头像 → **同 appid+同 loginCode（stub 下解析同一 openid）不带 phoneCode 再登** → 断言 `(200,0)`、`needPhone=false`、userId 与首登一致、accessToken 非空；并查库该绑定行仍 active 且 `mobile_hash` 与绑定时不变。不带 phoneCode 的意义：若资料更新意外破坏了绑定有效性（mobile_hash 判定），此处会走「作废+重绑」分支返回 needPhone=true，从而暴露回归 |
| AC9 | 真 PG 并发：两线程并发 PUT（不同 nickname），两请求均 `(200,0)`，最终 GET 为其中之一且资料行无损坏（last-write-win） |
| AC10 | 单测 `StaffProfileServiceTest` 全绿（**纯 Fake store 单测**，不含 system_users 真实写入——真 PG 证据由 e2e AC4/AC9 提供）：昵称/头像校验边界与类型契约、avatar 键语义四态到 store 的委托参数、业务异常错误码 |

## 八、非目标

- 头像文件上传本体（基-1 资产/文件服务提供通道后另批接 v3）
- 员工自助更换手机号（含 pf_wechat_binding 同步）——见 §九①
- 管理员修改员工资料（成员管理通道，已有 /platform/org/staff 域）
- 昵称/头像内容合规审核（基-1 asset 票据制自带扫描，届时头像走资产通道自然获得）
- v3 端 profile 页之外的其他页面接入

## 九、待决事项（不阻塞，按推荐默认继续）

| # | 事项 | 推荐默认 |
|---|---|---|
| ① | 员工自助换手机号：属**待建能力**（F3）——账号更新 + 旧绑定作废与令牌吊销（`revokeAllForUser` 端口已有）+ 成员管理控制器接线 + 重新授权流程；是否做、何时做。另：本接口手机号只读不覆盖旧入口——`PUT /system/user/profile/update` 仍接受本人 mobile 且不联动绑定/令牌，旧入口治理（收敛或下线该字段）同批评估 | **不做**，登记 00 §四b 遗留；近期换号需求走底座管理端或由管理员重建账号，不在本批承诺范围 |
| ② | avatar 当前可存任意外域 URL；是否限本域资产 URL | 本批不限（URL 口径 + ≤512 长度）；基-1 就绪后评估收紧为 assetId 引用（届时为契约变更，需迁移存量） |
| ③ | v3 端头像选择当前 `wx.saveFile` 本地路径，接后端后本地路径不可当 URL 传 | v3 接入批先只接姓名同步；头像在基-1 通道就绪后再接（避免本地路径污染 avatar 字段） |

## 十、v3 接入口径（本批随段推进，07 §三.5）

- v3 侧在 `feat/login-dual-channel-20261031` 或另立分支推进；改动收敛在 profile 编辑视图（`pages/account/index.js` view=profile 的 saveProfile）+ 一个薄视图模型文件（仿 `utils/platform-todos.js` 纯函数模式）。
- 姓名：输入 → `PUT /platform/profile/mine` → 成功后写回本地 storage profile.name（离线兼容：请求失败保留本地保存并提示）。
- verify 断言脚本随批（仿 finance-desk verify 口径）：断言视图模型纯函数（字段映射/打码/校验）不依赖 wx。

**落地记录（2026-11-01）**：v3 分支 `feat/profile-api-integration-20261101`——新增 `utils/platform-profile.js`（normalizeNickname/mergeProfile 纯函数，零 wx）+ `saveProfile` 改双写（有 API token 时 PUT 后端，失败降级本地保存提示「已本地保存，联网后同步」）+ `scripts/verify-profile-api.cjs`（校验/归一化/合并断言，绿）；v3 主 verify 套件（npm run check）passed。GET 拉取同步（登录后以后端覆盖本地）不在本批，随消息中心批评估。
