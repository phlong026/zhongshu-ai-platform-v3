# 六岗位闭环校准与修复计划（v4已审查，实施中）

## 目标与边界

用户要求真实打开微信开发者工具，监控请求/业务码/控制台，校准并跑通业务、工程、门店财务、门店老板、平台设计、平台财务完整流转；界面与流程参考已确认开发版 1.0.20261030.3，源码8f5e6fb3c42fe926f7a1880c0bdffe4ce1d07bf1。众墅甄选/加盟商服务只保留展示，延期交易建设。不上传、不切换现有微信开发版体验版，不改A。

前端修复基线98e7d2b2，新分支codex/fix-role-workflows-20261115。后端在46cc52ea创建独立工作树six-role-flow-20261115，保护原工作树他人暂存文档。运行B pg,integration，platform.zszhj.com，API PID3648973/release/platform-46cc52ea.jar，当前SHA256 a336f1d128f5aab4f72082f3e4d1891244bf84f69d8c69d288b47e3a8e9168fd。只允许B修复与验证。

主链复用真实测试客户2121799549299286017、需求2121802662328492033、预算2121802700861562882、合同2121804604685508610。新建独立工程2121825845924421634只作工程专项，不冒充主链唯一工程。测试账号145有六岗，无总部老板；同账号切岗不证明双人自审门禁通过。

## RALPLAN-DR

原则：1沿旧版有效真实流程恢复交互而非恢复本地伪数据；2前后端共同契约，服务端控制资金/资产/岗位；3同客户单据可追、幂等、金额分/不透明长ID；4最小复用现有表/Port，失败可见；5每个增量可独立测试、提交、B回退。

决策驱动：真实六岗接续；私有资产跨组织安全；双边资金净额与退款并发正确。

选项A（推荐）：使用现有中立franchise-api Port与既有委托/进度/移交存储形成权威关系；补设计资金双边对账和下发成果资产引用，委托范围下载授权由不可变下发/移交记录派生，不放宽通用资产接口。优点复用、少重复真源；代价需要跨模块事务/生命周期契约测试。

选项B：新建独立订单/设计工单/资产grant/资金ready事件表并复制旧状态。优点授权显式、未来可扩；缺点多个真源、幂等与撤销/退款同步面扩大，超出本轮。若现有下发记录不足以安全表达版本授权，才采用独立grant表并给出理由。

排除：直接把checked改confirmed后使用虚假文件字符串、markReady开发辅助端点或永久资金就绪标志；这些不能证明真实成果与退款后门禁。

## 已有证据（需运行时逐项复现）

- 前端workflow/index.wxml:40客户业务办理缺委托自然入口；workflow/index.js:38白名单未转到专属委托页。
- 前端workflow/commission/index.js:187-270：confirmed/checked、文件对象/字符串、ID语义不一致且提交响应被丢弃。
- 后端CommissionController.java:59-81,119-165：现有DTO与命令契约；CommissionService.java:265-322,544-578：设计门禁/allowedActions依赖旧ready字段。
- HandoverService.java:77-114,224-237：文件目前是未校验任意字符串；AssetAccessService.java:66-109,132-139：属主组织隔离，不能直接跨组织取总部资产。
- FinanceController.java:223-240,490-505：design实收与项目金额聚合；FinReceiptService.java:148-168：design首尾款来源与净额基础。
- 旧版utils/design-receipts.js:100-113要求门店已付与总部已收均足额。当前无正式双边放行调用链。
- 旧版新recordFlow在utils/engineering-records.js:67-68节点完成即留单人PASS；不把残留旧classic整改页另接第二状态机。

## 实施增量与接口决策草案

1. 委托入口/上下文：已实测主客户CustomerScheme数组为空，SalesQuote不会自动生成BUDGET。正常参考预算保存应由销售/CRM在同一事务按真实报价冻结尺寸生成版本化BUDGET，避免前端双写；不改GET造成隐式写入，不拿最新报价冒充已有预算版本，旧测试客户可通过正常UI重新确认预算生成新合法版本。新增按客户精确上下文查询（或复用能提供同等权威数据的现有接口），覆盖已有/未建委托、真实客户、最新预算/报价和规则；详情和命令始终用commissionId，新建用customerId；提交接住真实返回ID。前端自然入口恢复，表单保留、版本变化重新确认，按allowedActions显示按钮。现有标准面积/历史首尾款模式分别守契约，不盲目合并。
2. 双边资金：AREA_FULL为.3的新标准一次性全额（first=tail=0，FULL单一期次），LEGACY_SPLIT为历史首尾两期。新增独立总部面积计价策略API（rate/scope/recipient/version/active），不能继续调用只允许门店老板的历史design-policies。AREA_FULL锁定BUDGET面积×总部版本单价并免逐单老板审批；旧Checkout.paid是模拟承诺，现按R03-B补为门店有效已付+平台有效净收均足额，complete/dispatch都复核同一个FULL阶段。历史complete仅需门店首款已付足，dispatch需双方首尾均足额。中立DesignFeeSettlementPort（最终名称由架构核对）。费用直通/老板批准时按submission/billing version+stage幂等生成门店设计应付；付款关联该应付；总部收款关联同委托版本和stage。查询双方有效已付/净收，退款和支出退回实时冲减；complete/dispatch、allowedActions和待办共用判断，端口缺失fail closed。避免CommissionService→FinanceService→DesignFeeProgressApi→CommissionService循环，可由只依赖mapper的adapter实现。明确定价版本/首尾阶段及当前版本，旧记录不伪迁移；并发资金动作与门禁采用既有统一事务/锁协议，需架构审查确定。
3. 私有正式成果：增加DESIGN_DELIVERABLE（PDF/JPG/PNG）及可回退CHECK迁移；只允许平台设计上传；正式下发校验ACCEPTED、属主组织、三份成果/图纸/审核依据和图纸版本并不可变保存。业务移交继承锁定下发文件，只补客户确认真实资产、日期、交底、真实工程接收人。提供委托/下发版本范围的下载票据，验证参与组织、岗位和当前/可读历史版本；不能放宽通用跨组织访问。新写拒绝任意伪文件；历史读取显式展示缺失。
4. 财务与经营对齐：门店设计应付/付款入口、平台同委托首尾款实收入口及关联进度；项目账接现有聚合并补权威分页明细（含收付退款/退回），接续工程项目与前置委托设计费用。不可用前端截断列表拼假账。
5. 主链真实UI：同客户提交委托→门店老板需要时审批→门店/平台财务首款→平台设计完成→双方尾款→平台设计真实成果下发→业务客户确认/工程移交→指定工程接收→同一统一合同三期、准备/实际开工、材料劳务、节点留痕、更正撤回、回款→交付结算归档→门店老板经营/双方财务回读。业务依赖先后优先于岗位列表顺序，不中途塞已完成状态。
6. 检查与交付：每个增量红绿回归、静态检查、私有凭据扫描、独立代码审查；B候选JAR完整上下文/真实PG测试，B备份后仅部署B；官方SDK真实微信业务会话逐控件操作，记录前后单据/请求/日志/原生截图。结束保留修复分支和证据，不上传小程序。

## 预检风险（三种）

A. 仅总部收款或旧ready标志让资金不齐仍设计放行：测试双边不足、退款/退回、重复与并发、门禁/待办一致，持久化审计；不通过不得进入下游。
B. 总部私有资产通过字符串或通用接口泄露：测试伪ID、未complete、错误类型/属主、跨门店、旧版本、签名下载；只按显式委托关系授读，拒绝客户端URL/路径。
C. B迁移/部署串A或不可回退：核对进程/JAR/DB名、保存B数据库与JAR快照、检查迁移undo与有数据边界；A JAR/配置/启动时间前后指纹不变，不改用户微信版本。

## ADR草案

选择A，以真实资金与正式成果事实修通既有业务状态机；拒绝复制旧本地状态、扩建商城及第二套工程验收状态。后果是新增少量领域Port/API/迁移，需要共同锁与跨组织测试；将复杂度放在服务器契约，前端保持旧版交互语义。

可用原生角色：主代理集成/真实UI/B运维，analyst已完成旧版核对，architect方案复核，critic独立门禁，executor按不重叠模块实现，code-reviewer只读审查。当前App无OMX tmux，不宣称已启动team/hud。已有实施授权，非interactive审查通过后直接进入原生执行，不另建durable goal或请求上传权限。

## 2026-11-15 校准补充

AREA_FULL金额与期次源于reference utils/design-checkout.js:29-44, utils/sales-flow.js:94-105；LEGACY_SPLIT门禁见utils/design-receipts.js:100-131。单次模拟paid不能证明正式双边核收，两边事实是结合最终R03-B的明确实现补全。

真实工程上传PDF已ACCEPTED，但原生预览失败原因是B DownloadUrlView使用相对 /admin-api/franchise/assets/{id}/content?token=...，内容端点要求Bearer+X-Post-Id+tenant；共享下载helper必须按既有b-asset-upload策略绑定当前B源并带身份，仅外部签名HTTPS不带凭据，校验HTTP/JSON错误和迟到会话。对外URL/票据不得持久化或日志输出。

## v3 冻结契约（覆盖上文草案中不明确处，供 Architect→Critic 复审）

### 模式、预算与身份

- AREA_FULL：总部独立 `design-pricing-policies` 版本化 rateCents/scope/recipient/active；取已冻结BUDGET面积，服务端BigDecimal计算到分（正数HALF_UP），冻结 area/rate/ruleVersion/totalCents，firstCents=tailCents=0，唯一FULL期次。complete/dispatch均要求门店净付与平台净收足额；这是R03-B对旧模拟paid的有意真实业务补全，不声称旧版已有双侧真实核收。
- LEGACY_SPLIT：保留门店历史design-policies和例外老板审批；FIRST在批准时建应付，complete要求门店FIRST净付足额并在同事务生成TAIL应付；dispatch要求FIRST/TAIL两侧均足额。
- Quote创建沿既有 `advisory → replay → rows`，规范化指纹加入width/depth/floors/area/address/priceVersion/items/total。正常完整尺寸预算同事务生成CustomerSchemePort BUDGET并存schemeId/version关联；失败全回滚；已有测试客户从正常预算UI重新保存，不GET隐写。
- GET `/franchise/customers/{customerId}/commission-context` 返回 customerId、commissionId?、latestBudget?、feeOffer{mode,ruleVersion,area?,rateCents?,totalCents,firstCents,tailCents,scope,recipient}、commission?；访问先CRM可读校验，销售无需调用老板专属策略接口。
- POST新委托：AREA_FULL `{mode,budgetVersion,feeRuleVersion,text,scope,attachmentAssetId?,confirmed}`；LEGACY_SPLIT `{mode,budgetVersion,text,scope,totalCents,firstCents,recipient,attachmentAssetId?,confirmed}`。提交事务按customerId+budgetVersion+feeRuleVersion精确加载并复算：AREA_FULL任一不是当前有效版本都显式stale拒绝，LEGACY_SPLIT也须budgetVersion精确为当前版本；不得静默换最新。attachmentAssetId可选但若填写必须是本门店ACCEPTED的CONTRACT_ATTACHMENT。响应保留现有详情并明确commissionId；后续路径一律commissionId。

### 资金 Port、来源和锁顺序

- 中立 `DesignFeeSettlementPort`：Mode(AREA_FULL/LEGACY_SPLIT)、Stage(FULL/FIRST/TAIL)；`ensureInitialPayable(commissionId,submissionVersion,PostSnapshot actor)`、`ensureTailPayable(...,PostSnapshot actor)`、普通投影read与事务专用lockAndRead；StageState含expectedCents、grossPaid/expenseRefund/storePaidNet、grossReceived/refund/hqNet及双方ready。
- `CommissionQueryPort.CommissionFeeView` 增冻结mode/customerId/budgetPricingVersion/rate/recipient/stages。由mapper-only CommissionQueryAdapter提供，finance settlement adapter只依赖mapper/JdbcTemplate+此Port，禁止反依赖CommissionService形成循环。
- 设计应付不可变source_type/source_ref/customer_id；唯一(company_id,source_type,source_ref)，sourceRef含commissionId+submissionVersion+stage。付款必须关联对应应付，归属不能由客户端伪造。
- 平台设计实收新增originPaymentId和分笔amountCents，来源必须是同委托/版本/阶段的门店有效付款；累计有效核收不得超过该原付款净额。保留actual receivedDate/transaction/voucher，部分核收和退款均可追。
- 全路径锁序：所有design资金写入必须要求客户端幂等键，先取得命令advisory lock并在锁内replay → `design-fee:{commissionId}:{submissionVersion}:{stage}`事务advisory锁→ payable/payment/receipt/refund行锁。多阶段固定 FULL 或 FIRST→TAIL。资金写/修订/作废/退款/退回及complete/dispatch必须采用同一序；衍生应付用唯一源约束，不在持有阶段锁后反取命令锁。
- ensure*和lockAndRead显式使用@Transactional(propagation=MANDATORY)（或等价强制加入同一datasource事务的代理）并断言活动事务，禁止REQUIRES_NEW，传递PostSnapshot actor记录真实审计身份；持锁直到委托业务状态提交；allowedActions/todo用相同计算函数read投影，不用旧ready永久字段。退款前完成的事实不回滚；complete后退款阻塞dispatch；dispatch后退款保留下发历史，新增财务异常待办。

### 成果 manifest 与票据

- design-dispatch `{recipient,note,drawingVersion,resultAssetId,drawingAssetId,reviewAssetId,confirmed}`；三个资产均须ACCEPTED、DESIGN_DELIVERABLE、designOrg属主。保存不可变 `fr_commission_dispatch_manifest`（commissionId+dispatchSeq唯一，含submissionVersion/3assetIds/drawingVersion/designOrgId/actor/time）。不另建长期grant真源。
- packet-submit `{dispatchSeq,confirmationAssetId,confirmationDate,note,managerUserId,confirmed}`，正式三份成果完全继承manifest，不收客户端替换字符串；客户确认资产必须属于门店且为ACCEPTED的CONTRACT_ATTACHMENT，不接受其它类型。核验指定工程接收人有效且与提交人不同userId。
- 中立 `CommissionDeliverableAccessPort.requireReadable(actor,commissionId,dispatchSeq,slot)` 从manifest、companyId、handover manager及工程assignedEngineer派生授权。工程归属经mapper-only EngineeringProjectContextPort取得，不产生业务服务循环。
- 委托范围下载端点 `/franchise/commissions/{id}/dispatches/{seq}/files/{slot}/download-tickets`。短票据绑定consumerOrg/user/post、ownerOrg、scopeRef/version/slot；消费时重新验证当前任职与scope，才用owner组织服务账号读ACCEPTED字节。通用资产端点仍只允许owner组织。
- 共享下载helper只对明确B相对代理路径解析B源并带当前Authorization/X-Post-Id/tenant；外部COS签名HTTPS不带这些头，拒绝任意相对路径。HTTP200 JSON业务错误不可当文件，旧会话回包不可打开；签名URL/token不入持久化/日志。

### 项目账、回归与人员

- `/franchise/finance/projects/{projectId}/ledger?pageNo&pageSize` 返回summary+page<LedgerRow>；同一scoped CTE供两者，occurred_on DESC/id DESC/type DESC排序。通过fr_eng_project.source_commission_id纳入前置设计应付/付款/退款，拒绝跨组织。
- 工程按.3新recordFlow完成即PASS留痕/撤回/更正，不重建旧classic整改状态机。
- B隔离组织补充第二名普通工程测试员工（无老板/全局权限），按正常认证取得其身份以验证不同userId接收，不关闭移交/审批人员分离门禁。第一账号仍六岗且无总部老板；若辅助账号使用系统密码API而非微信绑定，需把此认证边界与双微信真机验收单列，不能冒称两个真手机微信通过。

### 并行ownership与迁移边界

- 首先由单一Contract负责人冻结franchise-api全部新Port和DTO，其他lane不可改签名。
- Contract/Commission：context、pricing、mode、state、manifest、handover、相关迁移和测试。
- Sales/CRM：Quote原子BUDGET、指纹、版本与并发测试；不得改commission或finance。
- Finance：settlement adapter、来源/核收、资金写锁、todo财务投影、project ledger、finance迁移及测试。
- Asset：DESIGN_DELIVERABLE、scoped ticket/content、LocalAssetService/资产迁移及测试；不得放宽通用跨组织。
- Frontend：commission/context/自然入口、finance两侧表单和账目、共享下载helper、生命周期回归；与后端DTO冻结后实施。
- 迁移只添加可空列/新表/放宽合法枚举，undo附有数据前置条件，B库与旧JAR备份完成后才应用；不触碰A或微信版本。新增真实设计资料/账目后数据库回退需恢复隔离B备份，不宣称git revert能回退外部数据。

## v4 最小审查补齐

全部design资金命令强制幂等锁→锁内replay→阶段锁→行锁；ensure*与lockAndRead以MANDATORY加入同一B数据源事务且传PostSnapshot审计；提交拒绝陈旧budget/pricing version；attachment/confirmation固定CONTRACT_ATTACHMENT。以上覆盖v3中“如有”等不确定表述。

## v5 实施审查修正：核收来源与真实回款关系

独立代码审查发现，阶段总额同时足额仍可能掩盖原付款已经退回/作废的孤立核收。保持实际支出退回可如实登记、原核收历史及原始净额不抹除；实时 headquartersReady/fullyReady 还须确保每笔有效总部核收的 originPayment 有效、同委托/版本/阶段，累计核收不超过其当前净付。来源异常阻断后续完成/下发，保留既有完成/下发事实并生成异常待办。付款作废是账务更正，存在有效关联总部净收时先完成对应收款的更正/退款。该项落实v4已冻结的逐笔来源约束，覆盖“原付款退回→另笔补付，阶段总额恢复但来源尚未校对”的反例。

项目账沿工程冻结sale_contract_id→fr_sale_stage_receipt与fr_eng_collection.receipt_id纳入真实三期回款及其退款，不能只依赖fr_fin_receipt.project_id。summary和分页仍共用同一受控CTE，不按客户名称/手机号模糊归账。

## v6 原生联调后的工程采购校准

对照8f5e6fb3的engineering-records.paidOut：采购条款由门店财务/财务主管/门店老板维护，工程员工负责计划与到货；存在未作废的已核实付款即锁定条款，支出全额退回导致净额为零仍不解锁。后端在应付行锁内核对本组织有效原付款事实，统一输出Boolean termsLocked，前端只接受严格false才编辑。金额净额仍用于真实财务展示，不能兼任历史付款锁。新增真实PG复现覆盖全额退回、原付款真正作废解锁、并发付款及组织隔离；100项相关回归通过，独立复审P0/P1为0。

B项目账保持来源关联，补齐到既有工程材料/人工页的自然入口；门店老板从同项目工程付款记录进入同项目账，不增加总部老板岗位。众墅甄选/加盟商服务只保留现状展示，交易/履约不纳入本轮闭环，也不宣称已完成商城联调。

授权成果下载先核对不可变下发清单与参与组织，再按已确认属主读取现有ACTIVE资源账户；不能使用强绑定当前岗位组织的账户解析器去解析总部属主。不得切换全局session或创建资源账户，不扩大通用资产读取权限。
