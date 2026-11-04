# M03–M06 独立评审记录

评审者：backend_map；作者：audit_customer_design；日期：2026-11-03。
基线：小程序f2592ce、后端88cd000；只读源码核对，未改业务代码、未调用外部供应商或写库。
范围：四篇全文、核心证据的实际源码、问题分类、跨模块任务边界与依赖、正负验收。
当前结论：修订后独立复核PASS，允许按本文明确边界本地定稿；本记录不表示业务整改已完成。

## 1. M03 内容创作、发布与获客

- 已核growth/index.js:14-24和ContentGrowthService.java:26-65：后端publications/leads为状态计数对象，perPublication使用id；前端当数字并要求publicationId，M03-I02成立，P2可保留。
- 已核growth/index.js:10-12、:20-22、:37-38：capture初载来自本地发布集合，平台拉取跳过capture，来源要求publicationId；M03-I03接入缺口成立。
- 已核growth/index.js:11-12：contents/leads仍来自G.load；I04不是服务端丢客，而是在线真实明细未回读，当前措辞正确。
- studio错误路径有真实源码证据，但它与M11-I01同源；M03可以描述对内容链的影响，不应独立派第二份修复。
- 内容自动发布、供应商实证、通知真实送达均保留边界，没有把人工登记等同自动平台发布。
- 跨域链publicationId→lead/customerId→绩效有连续对象与逆向条件；仍须真实环境验证统计定义。
- R2：M03-T03依赖M04-T03，后者再依赖“M03来源合同”，需明确来源契约先冻结，不能等待M03-T03全部完成。
- R3：M03-T01应改为引用M12-T01/A→M11-T01修路径→联合绿；M03仅负责内容链验收，避免重复工作量与任务归属。

## 2. M04 客资、客户、跟进与责任转交

- 已核customers/index.js:47-69：服务端每次50条，本地只slice10，hasMore却按已拉总数决定；20条时第11–20条无法正常浏览，I01成立，P1可保留。
- 已核workflow/index.js:21、:87-98：候选递增candGen但调用比较loadGen的stale；I02成立，不能仅靠切岗代次修复而忽略请求类型。
- 已核crm.js:7-15、LeadService.java:55-89、:109后续：pending/link/invalidate接口存在，前端无这些方法调用，LINK_REQUIRED处置缺口I03成立。
- 已核workflow/index.js:92-95、:191：姓名为映射键，同名后写覆盖，同名UI选择不能保证userId；I04静态成立，P2可保留。
- 同名整改使用不透明userId，不要求手机号公开显示；必要的辨别文案应遵循现有隐私政策。
- 权限表区分CRM本人/团队范围与Sales组织协作；不会因财务CRM不可读而误判所有交易越权。
- R2适用于M04-T03：其前置应是M03来源数据合同冻结，而非M03整条登记UI全部完成。
- M04-T04验证新旧责任人与历史实收较完整；真实离职交接政策仍按M01，不能自动改历史业绩归属。

## 3. M05 方案、预算、AI赋能与正式委托

- 已核commission.js为API定义，全文搜索没有对其import与submit/commands调用；workflow允许视图不含正式委托/packet，总部工作台build来自本地sales-flow；I01是真实UI接入缺口，后端API确已存在，P1可保留。
- 已核explore/platform-bridge.js:69-101：styles与cases共用platGen，reset成功跳过stale而失败仍检查，I02首屏失败不收敛及旧响应覆盖的静态因果支持作者E2报告。
- 已核platform-bridge.js:365-380：保存客户选择仅page(1,50)，无续页/搜索，I03成立，P2可保留；与M04的slice10不同。
- 已核CommissionService.java:213-220保存预算快照，HandoverService.java:164-178有明确“最新预算方案（v3蓝本basis口径）”注释。
- R4：I04不是已确认错误，应放独立待确认风险/验证项；不能在总数中当成待修缺陷。口径确认后可只补验证，不预先强制改后端。
- 参考预算与明细报价、AI参考图与正式下发成果已区分，没有新增线上客户签字等未经确认范围。
- R5：M05-T03负责委托提交、费用审批、到账门禁、正式设计完成/下发与反馈；packet上传/接收由M06负责，二者要拆清阶段产物。
- AI真实消费验证单列，不能用fake证明点数结算与供应商实际成功。

## 4. M06 报价、合同、收款与移交

- 已核SaleDeliveryService.java:75后只推进销售交付节点；HandoverService.java:157-159实际调用engineering.receiveFromHandover，二者不同链路，文稿区分正确。
- I01是继承M05真实委托UI缺口，文稿已说明不重复计修复量；R5要求实施边界进一步落实到任务。
- 已核SaleContractService.java:257-278，SIGNED才首次核销，经BusinessReceiptPort写财务，工程另有合同/期款；没有证据证明已重复入账，I02必须保持待确认口径风险。
- 已核workflow/index.js:323-325先stale后handle401、http.js:74-78清会话增代次；支持风险假设，但尚未执行交易页面端到端错误分支。
- R1：I03目前标题“代码确认/健壮性缺口”过强，应改待验证风险；T01先模拟HTTP层清态后的真实页面调用，再据结果决定改动；不得计为已确认线上/页面缺陷。
- 当前HTTP200/body401系统问题归M02；不可把本项HTTP401顺序风险混作同一个已复现问题。
- R5：M06-T03只依赖M05正式下发准备与M07既有EngineeringPort接收契约，不等待M07全部项目功能完工，避免跨域全量依赖。
- 负向覆盖未签收款、已付作废保账、旧dispatchSeq、自交自接、失效接收人、重复接收及失败回滚，方向合理。

## 5. 证据与执行限制

- 上述为独立源码核实；未重跑作者所有VM，作者E2仅保留为作者实证，不冒称独立重现。
- 关键前端：/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3/miniprogram/pages/workflow/index.js:87。
- 关键前端：/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3/miniprogram/pages/workflow/explore/platform-bridge.js:76。
- 关键后端：/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/HandoverService.java:164。
- 纯内存/源码不能替代同一客户项目的真实角色/真实持久化/真实渠道验收。

## 6. 首轮沟通与复核状态

- 已向作者audit_customer_design发送R1–R5并抄送主审；只要求作者修订，不代写作者稿。
- 首轮结论为“修订后复核”；作者已完成R1–R5，评审者重新读取四篇对应修订。
- 业务实施与验证仍全部待执行。

## 7. 修订后复核与最终结论

| 意见 | 作者修订位置 | 独立复核结果 |
|---|---|---|
| R1 交易401分类过强 | M06-I03:72-79、M06-T01:93 | 标题已改待验证风险，不计已确认缺陷；先实际catch复现，确认后才修，依赖M02，PASS |
| R2 来源合同依赖含糊 | M03-T03:101、M04-T03:102 | 双方均以前置合同冻结为准，不互等整体验收，PASS |
| R3 studio修复重复 | M03-T01:99 | 明确M12门禁A→M11统一修→内容回归，本模块不重复计修复量，PASS |
| R4 最新预算口径差异 | M05-I04:81-88 | 不计已确认缺陷，待口径确认，现有合理则仅验证，PASS |
| R5 委托与工程接收边界 | M05-T03:104、M06-T03:95 | 前者到正式下发/反馈，后者packet提交/退回/接收建档，仅依赖下发准备及既有端口，PASS |
| 风险状态收口 | M06-I02:70、M06-I03:79 | 已分别改待口径核对/集成验证、待复现；均确认问题后才产生修复，不再抽取为必修缺陷，PASS |

- 逐模块最终结论：M03 PASS，M04 PASS，M05 PASS，M06 PASS。
- 保留限制：P级仅用于当前整改排序；E1/E2支持问题判断，尚无新增真实数据库/真机/真实供应商/正式部署证据。
- M05-I04、M06-I02、M06-I03是待确认/待验证风险，不计入已确认缺陷总数。
- M03-I01与M11-I01、M06-I01与M05-I01按共享根因合并实施工作量，不因跨模块重复提及增加缺陷数。
- 允许文档本地定稿；业务整改和交付门禁继续保持待实施/待验证，不能将本次文档PASS写为项目已修复或已上线。
