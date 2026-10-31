# finance-desk 小程序接入 · 需求文档与验收标准

> 批次：后端化工程 · B1 接入　日期：2026-10-31　状态：V5（吸收 Codex R1-R4：13+5+3+2 P1 与各轮 P2）
> 流程：需求文档 → Codex 评审循环至 PASS → 开发 → 提交；决策类不确定点记 §10。

## 一、背景与目标

`pages/admin/finance-desk` 是 finance/finance-lead 岗位的财务工作台（首页 desk + 流水台账 flows），当前数据来自本地 Storage 演示账簿。B1 后端（franchise-finance 域）已交付账簿接口并通过 e2e。

本批目标：**财务岗登录后端账号时，finance-desk 的指标/台账/登记表单走真实账簿**；未登录后端保持演示数据不变。

## 二、前置依赖（monorepo 补丁 6 项，纳入本批提交）

| # | 补丁 | 说明 |
|---|---|---|
| P-1 | `GET /franchise/finance/payments?status` | 付款列表（缺）；selectByCompany 新增，排序 create_time DESC,id DESC |
| P-2 | 付款登记写入 projectId | FinPaymentService.register 从 payable.getProjectId() 带入付款记录（**修复项目账漏计本批登记付款的缺陷**）；存量无付款数据无需回填 |
| P-3 | registerPayable 幂等接入 | FranchiseCommands replay+record（同键异内容冲突/重放返回首次 payableId）；Controller 接收 Idempotency-Key |
| P-4 | FinanceTodoProvider appPath 改为 `/pages/admin/finance-desk/index?view=flows` | 到账待确认待办落地本批新增的「到账待确认」区块（原 collaboration 页本批不接入） |
| P-5 | 到账确认幂等接入 | confirmBank 经 FranchiseCommands replay/record（重放返回首次 confirmId；同键异内容冲突）；Controller 接收 Idempotency-Key |
| P-6 | 待登记应收生产：**本批以 dev 注入通道闭环验收**（BankFlowDevController 扩展：可选创建 status=待登记 的 intercompany 应收，仅 local）；真实生产链（intercompany 移交事件自动建待登记应收）为跨域联动，**记预留 D10 随 B2/B3 落地**——到账办理闭环的验收用该通道，诚实标注为测试基建 |

同时补 e2e 步骤（AC17 扩：payments 列表/越权/付款 projectId/registerPayable 幂等/同键异内容冲突/两组织隔离）。**其余接口 B1 已齐**。

## 三、接口契约表（api/finance.js 全量方法；方法→路径→参数→响应）

| 方法 | 路径 | 参数 | 响应（data） |
|---|---|---|---|
| receipts(status?) | GET /receipts | status 可选 | `FinReceiptDO[]`（id/companyId/projectId/name/amountCents/status/receivedDate/transactionNo/revisionVersion…） |
| payments(status?) | GET /payments（**本批新增**） | status 可选 | `FinPaymentDO[]` |
| payables(status?) | GET /payables | status 可选 | `FinPayableDO[]` |
| registerPayable(body, idemKey) | POST /payables | stage/totalCents/recipient/projectId/agreementRef | `{payableId}`（幂等重放，P-3） |
| refunds() | GET /refunds | — | `FinRefundDO[]` |
| registerReceipt(body, idemKey) | POST /receipts | 见 FR5 | `{receiptId}` |
| registerPayment(body, idemKey) | POST /payments | 见 FR5 | `{paymentId}` |
| registerRefund(body, idemKey) | POST /refunds | 见 FR5 | `{refundId}` |
| bankFlows() | GET /bank-flows | — | `FinBankFlowDO[]` |
| confirmBank(body, idemKey) | POST /bank-confirms | bankFlowId+receiptId | `{confirmId}`（幂等重放返回首次 confirmId，P-5） |
| projectAccount(projectId) | GET /project-accounts/{id} | — | 聚合四值 |

`overview()` 为**客户端聚合函数**（platform-finance.js 内基于 receipts/payments/refunds 计算），无对应端点。

## 四、双体系切换判定（五态，非"未登录"单一条件）

| 态 | 判定 | 行为 |
|---|---|---|
| `guest` | 无后端 token | 整页 demo 原样，零请求 |
| `no-post` | 有 token 但 currentApiPost() 空（未选岗/任职失效） | 同 guest（01 契约：idle 不发请求） |
| `finance` | currentApiPost().roleCode ∈ {finance, finance-lead} | **本批目标态**：指标/台账/登记全部走后端 |
| `non-finance` | currentApiPost().roleCode 为其他岗位 | 整页 demo 原样（finance-desk 非其工作台；平台待办区块已由 B0 处理） |
| `expired` | 请求中 401（当前会话） | http 层清会话回调；页面数据清空回 guest |

岗位/组织一律取 API 任职（roleCode/orgId），**不读本地角色**。API 态下页面**任何路径不得读写 demo 账簿 Storage**（AC9 spy 断言），advances/focus/总部剩余指标等 demo 卡在 API 态隐藏或显示"暂无"（逐项见 §五矩阵）。

## 五、页面/动作矩阵（逐项：接入/隐藏/延期）

| 页面元素 | API 态行为 | 去向 |
|---|---|---|
| metrics 三卡（门店） | **接入**：本月已登记收款（Σ已登记 receipts 本月 receivedDate）/本月已登记付款（Σ已核实 payments 本月 payDate）/本月退款净额（Σ已核实退款） | 公式见 §六 |
| metrics 三卡（总部） | **隐藏**（design 收款正向链路未通，D2） | M5 |
| 待办事项卡 | **接入**：复用 01 platform-todos-page 单例状态（唯一请求源，与平台待办区块共同消费）；仅显示 fr-fin: 与其他 actionable 事项；partial/failedDomains 按 01 状态机 | — |
| shortcuts | 收款登记/付款登记/收支流水=**接入**（跳本页登记区/台账）；项目收支=**接入**（projectAccount 聚合弹层或跳项目台账，本批取后者）；确认到账/设计费用进度=**隐藏**（D2） | — |
| 正在办理卡（focus） | **隐藏**（demo 语义为材料预付款/设计订单，D3） | B3 |
| flows 台账 | **接入**：收款(+已登记)/付款(−已核实)/退款(−已核实) 三分类合并，按 receivedDate/payDate/date 本地月度过滤 | — |
| 收款登记表单 | **接入**：POST /receipts（sourceType=manual；name/method/receivedDate/transactionNo 必输规则见 FR5） | — |
| 付款登记表单 | **接入**：POST /payments；**payableId 选择器=payables(status=进行中) 下拉**；无可选应付时表单禁用并提示"请先登记应付" | — |
| 退款登记表单 | **接入**：POST /refunds；**receiptId 选择器=已登记 receipts 下拉**（显示可退余额=amount−Σ已核实退款，本地计算）；无可退收款时禁用 | — |
| 应付登记 | **接入**：POST /payables（付款前置；表单：stage/total/recipient） | — |
| 收款修订入口 | **延期**（修订留痕展示随 M5；不在本批表单） | M5 |
| 付款作废/退款作废按钮 | **延期**（权限已有但 UI 随 M5 统一；AC 覆盖接口权限 403 断言即可） | M5 |
| **封存全集（00 禁入清单）**：verify、correction-*（含 correction-request/approval）、receipt-supplement、payment-supplement、payment-review、payment-return、receipt-return、reconcile、finance-transfer、cross-evidence/query/reply/attach、**refund-request/approve/execute/review（旧申请审批执行链，与本批线下登记为不同动作，互不影响）**、performance-appeal-resolve、cost-confirm、project-close | **隐藏**（过滤实现=platform-finance 视图模型**返回新过滤对象**，不改 finance-views.js 公共配置；旧参数直达由动作层 assert 拦截兜底；partners/statement API 态入口同步隐藏、demo 态保留） | 物理摘除随批次 D 合并 |
| 跨组织补证 cross-* | **隐藏**（同上） | 同上 |
| **到账待确认区块（新增）** | flows 台账顶部：bankFlows(待匹配) 列表 + 每行「确认入账」按钮（选择对应待登记应收后 POST bank-confirms）——fr-fin 待办的办理落地闭环 | — |

## 六、资金口径与登记契约

**指标公式（逐卡）**：
- 本月已登记收款 = Σ receipts(status=已登记 且 receivedDate 在本月).amountCents
- 本月已登记付款 = Σ payments(status=已核实 且 payDate 在本月).amountCents
- 本月退款净额 = Σ refunds(status=已核实 且 date 在本月).amountCents（**独立展示，不与收款/付款合并**）
- **作废记录不参与任何统计**
- 月度字段：收款按 receivedDate、付款按 payDate、退款按 date；**业务日期为空时回退 create_time 的月（唯一规则，指标/台账/AC2/AC3 统一；时区=库存储时区 Asia/Shanghai）**——月度统计不丢记录，**无"不计月度"分支**
- 三组请求非一致快照：以各请求返回时刻数据为准，页面不做跨组一致性承诺（降级文案提示可刷新）

**登记字段映射（FR5）**：

| 表单 | 必填 | 可选 | 服务端校验（错误码） |
|---|---|---|---|
| 收款登记 | name、amountCents、method(bank/cash/other)、transactionNo(bank 时必填) | receivedDate(默认今日)、projectId、voucherFileId(本批不上传，D4) | 交易号重复 001/009 |
| 付款登记 | payableId(下拉)、amountCents、payDate | transactionNo、voucherFileId | 超额 010、payable 缺失 011 |
| 退款登记 | receiptId(下拉+显示可退余额)、amountCents(≤可退余额本地预校验)、reason | date(默认今日)、transactionNo、voucherFileId | 超额 005、来源非法 006 |
| 应付登记 | recipient、totalCents(换算规则同上)、stage | projectId、agreementRef | 幂等重放返回首次 payableId（P-3） |

**金额换算（FR4 细则）**：输入为十进制字符串——**原始串先校验格式 `^\d+(\.\d{1,2})?$`（含逗号即拦截——不做千分位去逗，杜绝 "1,23.45" 歧义**）；**拒绝超过两位小数**（0.001 拦截）；不接受科学计数法；分 = 元 × 100 精确整数（字符串拼接法：整数部分+小数部分两位补齐）；校验 `0 < 分 ≤ 10^12`；0 与负数拦截；**非法值零请求**。样例：0.29→29、0.001→拦截、0→拦截、"1,23.45"→拦截（格式非法）、上限 1000000000000 分→通过、上限+1 分→拦截。

**幂等键（FR5 细则）**：键 = `fin-{动作}-{uuid}`；**绑定"当前身份+动作+规范化请求体"**——提交中（在途）禁重复发送（按钮禁用）；失败重试**同键同内容**；**修改任一字段后生成新键**；服务端"同键不同内容"冲突（1099000000）提示"请刷新后重新填写"，不得自动换键重提；响应丢失（超时）时结果未知——提示"请刷新台账确认是否已登记"，不引导再次登记。

**错误处理三分类（FR6 细则）**：
- **登记失败**（code≠0 业务错误：005/001/009/010/006/011/003）→ 表单保留输入 + 具体中文文案（005→"超过剩余可退金额，请刷新余额"、001/009→"流水号已登记过"、010→"累计付款超出应付金额"、003→"数据已变化请刷新后重试"）；余额类错误同时刷新对应选择器余额
- **结果未知**（网络超时/断连）→ "网络异常，请刷新台账确认是否已登记"，不自动重试
- **刷新失败**（GET 失败）→ 台账/指标降级文案+重试；**POST 成功后刷新失败** → toast"已登记，列表刷新失败"
- 401 当前会话 → http 层清会话回 guest；403 → "没有该操作权限"（保留会话）；空列表 → 正常空态；响应形状非法 → 降级

**乱序隔离（继承 01 契约）**：页面级 loadGen + 全局 sessionGen + postKey 三元组校验，覆盖账簿加载、待办（复用 platform-todos-page）、表单提交回调（提交中切岗→丢弃响应不刷新表单）；切岗/登出清空账簿数据与表单；验收 A→B→A、同岗重登、手动重试覆盖、提交中切岗、卸载后迟到响应。

## 七、验收标准（可执行）

**node 单测（scripts/verify-finance-desk.cjs，纯函数+mock）：**

| # | 断言 |
|---|---|
| AC1 | 五态判定矩阵：无 token/no-post/finance/non-finance/401 后各分支正确（demo 零请求计数、finance 发请求） |
| AC2 | 台账映射：三组样例（receipts 已登记/payments 已核实/refunds 已核实）→ 合并台账 sign 正确、月度过滤（本月/历史月/空日期计入"全部"）正确 |
| AC3 | 指标：三卡数值从样例聚合正确；作废记录不计入；**空业务日期记录按 create_time 月计入月度**（创建于本月与历史月两条样例） |
| AC4 | 金额换算：0.29→29、1234.56→123456、"1,23.45"拦截、0.001 拦截、0 拦截、-1 拦截、上限通过、上限+1 拦截、空拦截；**非法值零请求** |
| AC5 | 错误码映射：005/001/009/010/006/003/**011（未关联应付，表单必填前置拦截）**/013（应付不存在，选择器失效提示）→ 各自文案；**到账确认 014/015/016/017/018 → 各自文案与刷新行为（016 后保留选择并刷新可确认列表）**；未知 code → 通用文案含 msg |
| AC6 | 封存过滤全集（00 禁入清单逐项）：API 态视图模型不含 verify/correction-*/receipt-supplement/payment-supplement/payment-review/payment-return/receipt-return/reconcile/finance-transfer/cross-*/refund-request/approve/execute/review/performance-appeal-resolve/cost-confirm/project-close 任何入口；**新增线下退款登记动作保留**（不同动作）；demo 态与原样一致；过滤返回新对象 |
| AC7 | 幂等键：首提交生成键；失败重试同键同内容；字段变更换新键；成功后再提交新键 |
| AC8 | 降级：GET reject → 降级态+重试；POST 网络异常 → "结果未知"文案；POST 成功+GET 失败 → "已登记，列表刷新失败" |
| AC9 | platform-finance.js 源码零 wx/Storage 引用；API 态处理链 spy 断言零 demo Storage 读写（执行链 spy 而非仅字符串检查） |
| AC10 | 乱序：A→B 岗旧响应丢弃；**A→B→A**；同岗重登（sessionGen）；手动重载覆盖旧响应；提交中切岗提交回调丢弃；卸载后迟到响应丢弃 |
| AC11 | 消费 fixtures/fin-sample.json（monorepo franchise-fin-b1-e2e.py 尾部导出扩展：receipts/payments/refunds 三类样例；输出路径打印）完整解析 |

**runtime（scripts/verify-finance-desk-native.cjs，自建 fixture 独立复现）：**

| # | 断言 |
|---|---|
| AC12 | 前置：脚本自行经 HTTP 造数（dev-setup→财务登记 manual 收款，**记录本次 receiptId/金额/日期**），不依赖历史数据 |
| AC13 | stub 登录 finance 岗 → finance-desk：实发 `/franchise/finance/receipts`（拦截断言三请求头）→ 台账含**本次登记记录**（精确 id/金额断言） |
| AC13b | **到账确认原生闭环**：dev 注入流水+待登记应收（P-6）→ 到账待确认区块渲染 → 原生点击确认 → POST bank-confirms 实发 → **应收变已登记、流水变已认领、台账仅增加一次、对应待办 key 精确消失** |
| AC14 | **付款主链**：创建应付→下拉选择→付款提交→台账负向行→后端 GET 精确确认；**退款提交**同模式；收款登记原生提交断言；**到账确认幂等**：同键同内容重放返回首次 confirmId 且无重复资金效果（业务状态已变化后重放）、同键不同内容 → 1099000000 |
| AC15 | demo 态（登出后端）→ 无 fr-fin 请求、页面回演示数据 |
| AC16 | finance-lead 登录 → 同 AC13（权限矩阵正向） |

**monorepo（本批前置改动验证）：**

| # | 断言 |
|---|---|
| AC17 | monorepo：GET /payments 列表+越权 403；付款 projectId 写入断言（登记付款后 projectAccount.paidConfirmed 精确增量）；registerPayable 幂等重放+同键异内容冲突断言；**confirmBank 幂等重放+同键异内容冲突断言（P-5）**；两组织数据隔离断言（A 公司登记 B 公司查询不含）；编译+既有冒烟不回归 |

## 八、涉及文件

| 仓库 | 文件 | 动作 |
|---|---|---|
| monorepo | FinanceController + FinPaymentService/Mapper + FinanceTodoProvider + FinBankService + **BankFlowDevController（P-6 扩展）** | **前置补丁 P-1~P-6**（§二）+ e2e 扩 AC17 |
| v3 | miniprogram/api/finance.js | 新增 |
| v3 | miniprogram/utils/platform-finance.js | 新增（纯函数+五态判定+过滤） |
| v3 | miniprogram/pages/admin/finance-desk/index.js | 接线（五态分支+乱序隔离+表单提交） |
| v3 | miniprogram/pages/admin/finance-desk/finance-home.wxml | 指标/待办卡条件渲染 |
| v3 | miniprogram/pages/admin/views.js 渲染层或视图模型层 | 封存入口过滤（返回新对象） |
| v3 | scripts/verify-finance-desk.cjs / -native.cjs、scripts/fixtures/fin-sample.json | 新增 |

## 九、非目标

collaboration 到账确认页接入（D2）、partners/statement/advances 视图（B3/D2/D3）、凭证上传 UI（D4）、收款修订 UI（M5）、封存流程物理摘除（批次 D 合并）、历史 Storage 账簿迁移（不迁移，切换边界=B1 上线时点）。

## 十、待决事项（按推荐默认继续，不阻塞）

| # | 事项 | 推荐默认 | 重议时机 |
|---|---|---|---|
| D1 | "合同待收"指标 | 本批以"本月退款净额"替代 | B2 评审 |
| D2 | 总部 partners/design 正向 | M5（总部财务角色） | M5 |
| D3 | advances/focus 卡 | API 态隐藏 | B3 交付 |
| D4 | 凭证上传 UI | 本批隐藏上传控件、不传 voucherFileId（登记提交不含凭证字段）；接口已备 | M5 |
| D5 | 台账分页 | 全量+本地月度过滤；>500 条服务端分页（monorepo 责任） | 数据量触发 |
| D6 | fr-fin:bank-confirm 待办办理落地 | 本批落地至 finance-desk 到账待确认区块（appPath 已改 flows）；事项级定位随 M5 | M5 |
| D10 | intercompany 移交事件自动建待登记应收（真实生产链） | B2/B3 跨域联动时落地；本批验收走 dev 造数通道 | B2 评审 |
| D7 | 修订留痕展示 | M5 | M5 |
