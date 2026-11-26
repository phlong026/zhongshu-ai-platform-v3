# 众墅之家 V3 V1.1 整改执行日志

> 执行依据：`docs/roadmap/V3-module-remediation-plan-20261125.md`
> 执行仓库：`phlong026/zhongshu-ai-platform-v3`
> 建立日期：2026-11-25（北京时间）
> 当前进度与[整改任务总表](V3-module-remediation-plan-20261125.md)同步，本文件后半部分保留历次执行和验证证据。

## 全部整改任务的当前状态

截至 2026-11-26 21:25（北京时间），已核对小程序主线 `6d193dbbc72c813e2def8cce6a3a6f6a71d6e78d` 和后端主线 `2a1f5d0e07a7af1a8e2a898f62542921ee6c28ba`。整改总表与执行日志在同一提交更新本节。每项完成后将状态、对应 PR、测试和剩余验收同时记入这两份文档。后文的阶段记录保留当时的检查结果。早期原计划和快照中提及的付款计划审批岗位，按本节 B03 已确认的签约锁版规则执行。

本轮同一脱敏客户办完 8/8 步，开工后第二期收款也已核对，合同实收 600 元、余款 400 元。独立恢复样本通过空库还原、Redis 和附件核验。品牌、未绑定门店及覆盖地区已有处理原则。真实积分科目价格冲突待取得同期源、目标差异后逐项决定。

### 2026-11-26 晚间业务决定与开发分工

项目负责人确认以下口径。此节记录开发依据，各项以对应 PR 的最终提交及联测结果登记交付。

- **公司积分**由平台超级管理员加到门店所属公司的共享积分池。接续 #245/#246，把线上新充值改为默认关闭的可配置开关，并保留原有代码。关闭时小程序隐藏充值入口，服务端拒绝新充值。已发起的订单仍可核对到账、查历史和办理退款。
- **第 04 步免带看**由经办人填写原因，再主动点击「跳过带看」。计入八步进度，页面和历史显示「已跳过」、原因、经办人和办理时间。实际带看继续记录工地和客户反馈。
- **施工合同及付款计划**由业务人员在签订施工合同时与客户确认并录入。可以一次付清或分多期，每期记录金额及付款日期或触发的工程节点，各期合计等于合同总额。合同和对应版本的计划随工程移交，签约前已实际到账的款项一并带入。工程按期联系客户并登记到款金额和凭据，支持部分到账。财务核对实际到账及入账，业务、工程按同一合同和期次回看已收、余款及付款条件。正式施工合同签署时锁定当时的付款计划版本。签约后任何岗位均不得调整期数、每期金额、付款日期或触发的工程节点。工程继续按该版跟进收款，财务核对实际到账。
- **第 07 步**只在正式施工合同签署后完成。单独签设计服务合同可归入设计委托档案，第 07 步继续待办。两种情况分别核对客户进度。第 06 步保留正式报价沟通判定，独立快速预算继续用于前期测算。
- **现场排期**由项目负责人负责部署，三家门店试点名单和时间延后提供。团队先推进开发、隔离前后端联测及演练准备。正式薪酬公式、公共费用分摊待用户提供后再实现。

**B03 签约锁版已交付。** 正式施工合同签署时锁定当时的付款计划，签后所有岗位只读期数、每期金额、付款日期和触发工程节点。原计划替换和旧版合同金额调整入口均已限制。签前保存遇到网络中断后重试，小程序先读取原合同。工程按签约版本跟进收款，财务核对实际到账。升级前若已签合同有过调期，系统锁住现行版本，保留历史调整和原有实收供回查。[#277](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/277)、[#278](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/278)。

#### 已完成的配对核验

截至 2026-11-26 20:11（北京时间），工程分期收款和财务核对由 [#274](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/274)、[#275](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/275) 交付。客户跟进、免带看、施工签约、工程收款和充值开关的功能版本及总回归结果见下表。后续 [#277](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/277)、[#278](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/278) 已完成 B03 签约锁版，固定配对及验收见下表。

**第 04 步免带看。** 后端 [#272](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/272) 提交 `c8683cd38edb926b4ca6c0d6ccff05d3c35ef907`，小程序 [#273](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/273) 提交 `8cb3e707b67395ef2a72d664f5d2946c972e33be`。原生办理页经 Spring MVC 和 PostgreSQL 实际往返 7 次。经办人填写原因主动跳过，进度从 0/8 到 1/8。相同请求重试只记一次，后续附加资料独立保存。跳过事实包含原因、经办人和服务端时间。办理历史和总部只读详情可区分实际带看，其他步骤的跳过请求返回 404。此配对的后端 37 项、小程序 1,656 项、岗位首页 148 项均通过。

**第 07 步施工签约。** 同一客户把已签设计服务合同作为原委托附件上传后，施工签约仍待办，正式施工合同原单数为零。签署该客户的施工合同后，第 07 步自动完成并显示合同编号与已签附件，第 08 步仍待办，设计服务合同可从原委托下载。门店与总部显示「施工签约」。固定配对为后端 `c7b91109e3364fecfad6b4f3f68d81c37e4b6bc6`、小程序 `78b747503602b1f6485190e7e1c7a65bf048bd1f`。三类 PG 回归 44 项，小程序完整检查 1,655 项，岗位首页 148 项，均通过。后端 [#270](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/270)，小程序 [#271](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/271)。

**施工合同付款与工程收款。** 后端 [#274](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/274) 提交 `c4def6dc05a2d349319395e59eb07cbf7846ae72` 配小程序 [#275](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/275) 提交 `598ec656e46824a2e9a98e9286cb0cd8c355f801`。1,000 元正式施工合同分两期。首期 400 元，签前实收 100 元沿原单带入。工程申报 150 元，财务退回补件，经办人沿用原银行流水号换凭据再提交。财务核实后，业务和工程均见首期实收 250 元、余款 150 元，二期仍应收 600 元。重复确认仍只有两笔实收，原移交包保留提交时的 100 元。仅有设计合同时可归档、退回，正式接收建工程要求已签施工合同和有效的同版付款计划。指派和撤权、其他工程的金额及凭据隔离均通过。固定双端 PostgreSQL 专项 10 类 165 项、小程序 1,655 项、岗位首页 148 项通过。独立复验 PostgreSQL 80 项、原生专项 83 项通过，和前述用例有重合。旧版小程序 b0f22b7f1d4a12a867eca990c2abfc243a35cb3d 另验同一合同从移交到开工通过。

**公司积分与充值。** [#245](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/245) 的后端及管理台提交 `95dac66fe58cfe287ad90acafb6d670673b2e0e8` 配套 [#246](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/246) 的小程序提交 `433d5f454ab6903466c2cec8c4dfd2ff15e8e4fd`。平台超级管理员可给门店所属公司的共享积分池加分。服务端配置 `platform.wallet.payment.online-recharge-enabled` 默认为 false，保留原支付渠道、建单、签名及唤起代码。关闭时阻止新充值，既有订单继续接收到款通知、核对及办理退款。小程序显示本岗位的当前订单，授权管理台的「历史充值订单」可按组织分页查询旧钱包与公司积分的订单。八类后端测试 103 项、管理台 45 项、小程序 1,653 项和岗位首页 148 项通过。微信开发者工具分别验证关闭、开启、已选套餐后关闭，以及原单从支付确认到到账及退款。现场真实支付待用户排期。

**客户跟进页及入口。** [#268](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/268) 已合入小程序，合并提交 `fdfab70fe4ac1ce51c1e898c8cee171bcc61a85c`。微信开发者工具验证进度轨和展开列表各八项，支持从第 08 步切回第 02 步。列表、办理页、工作流及总部只读页统一使用「客户跟进」。

### 13 个执行节点

| 节点 | 任务 | 当前状态 | 已验证及待办 | 对应 PR |
|---|---|---|---|---|
| 00 | 基线与任务冻结 | 已完成 | 两条主线、历史差异、复用评估和执行规则已登记。#175 | [#175](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/175) |
| 01 | 财务更正续办 | 已合并，待财务岗位验收 | 更正后丢失回执可续办，专项接入标准 CI。#171、#195 | [#171](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/171)、[#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) |
| 02 | 门店公开地址与总部权限 | 已合并，待部署和账号验收 | 独立公开地址、同品牌过滤及更换地址重新授权。#196、#201 | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196)、[#201](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/201) |
| 03 | 工程收款、到货与交付 | 节点 03 的代码及跟进 PR 已合并，待岗位现场验收 | 工程收款 #199/#202/#209、材料批次及更正 #204/#206/#207、交付结算 #208 和负责人核验 #210 已合并。#226 补齐原单分次补发，#244 使工程和老板首页随实际到货消项。#274/#275 已验证合同同版分期移交、工程申报部分到账和财务退回补件、核实入账。施工签约时锁定付款计划，按期实收和退款可继续核对。 | [#199](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/199)、[#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202)、[#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204)、[#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206)、[#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207)、[#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208)、[#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209)、[#210](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/210)、[#226](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/226)、[#244](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/244)、[#274](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/274)、[#275](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/275)、[#277](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/277)、[#278](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/278) |
| 04 | 发布与客资 | 内容来源至实际开工的回归已合并，待渠道和真机验收 | 作品发布后回填链接，首笔咨询建档，第二笔关联同一客户。#252 已验证从实际开工反查两笔咨询、原作品和发布记录。外部发布渠道及试点账号待 B06。 | [#164](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/164)、[#168](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/168)、[#155](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/155)、[#84](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/84)、[#252](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/252) |
| 05 | 历史 PR 差异 | 差异核查已完成，真实渠道另验 | 逐项对照 #26、#27、#106、#107，核对主线替代接口、并发保证与退役流程。 | [#26](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/26)、[#27](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/27)、[#106](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/106)、[#107](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/107) |
| 06 | 我的作品 | 双端已合并，待原岗真机验收 | 个人回收、恢复、原稿和历史发布来源。后端 36 项、小程序 49 项通过。 | [#216](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/216)、[#218](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/218) |
| 07 | 内容获客统计 | 签约、净实收及页面口径均已合并 | 按客户编号去重，分别统计关联咨询客户中的有效签约和净实收。#227 的页面用语与接口口径一致。正式成交事件与多触点分配待业务确定。 | [#213](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/213)、[#214](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/214)、[#227](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/227) |
| 08 | 设计与快速预算 | 已合并，待门店真机验收 | 保留独立与项目快速预算、计价、扣点、保存、历史及导出。平面与立面 AI 生成已退出。后端 252 项、小程序专项 189 项及两端 CI 通过。 | [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211)、[#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) |
| 09 | 工程成本与项目账本 | 材料、项目账本、质量整改与施工延期已合并，待现场验收 | 实际收货、分次补发、领用和成本可追到原单。质量整改兼容旧版登记再分派。施工延期分别显示原工期和修订排期，保留原因、措施、恢复日和办理历史。撤岗、失效链接与弱网重试已复验。 | [#215](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/215)、[#219](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/219)、[#217](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/217)、[#225](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/225)、[#224](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/224)、[#226](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/226)、[#231](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/231)、[#232](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/232)、[#237](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/237)、[#242](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/242)、[#243](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/243)、[#244](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/244) |
| 10 | 客户、委托、合同至开工 | 八步办理、外部委托、签约及工程开工的代码均已合并，待用户排期验收 | 同一客户贯穿内容咨询、八步跟进、正式报价、施工签约、指定工程负责人接收和实际开工。有原因的免带看计入进度，实际带看单独留存。设计合同可归设计委托，正式施工合同签署后才完成第 07 步。各期付款条件和实收可追到同一合同和原始凭据。施工签约时锁定付款计划，按期实收和退款可继续核对。 | [#221](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/221)、[#222](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/222)、[#249](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/249)、[#250](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/250)、[#251](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/251)、[#252](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/252)、[#253](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/253)、[#254](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/254)、[#255](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/255)、[#256](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/256)、[#257](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/257)、[#258](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/258)、[#259](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/259)、[#262](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/262)、[#263](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/263)、[#264](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/264)、[#265](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/265)、[#266](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/266)、[#267](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/267)、[#268](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/268)、[#270](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/270)、[#271](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/271)、[#272](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/272)、[#273](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/273)、[#274](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/274)、[#275](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/275)、[#277](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/277)、[#278](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/278) |
| 11 | 绩效、公告、培训与公司额度 | 绩效基础、工资实付、公司共享积分及充值开关已合并 | 超级管理员为门店所属公司添加共享积分。新充值默认关闭，历史订单、既有订单到账核对及退款继续可用，原支付代码可按配置恢复。正式薪酬公式待用户提供。 | [#157](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/157)、[#160](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/160)、[#220](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/220)、[#228](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/228)、[#229](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/229)、[#230](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/230)、[#235](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/235)、[#240](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/240)、[#241](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/241)、[#245](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/245)、[#246](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/246) |
| 12 | 迁移、配对与上线 | 制品迁移与隔离恢复已验，目标环境待验 | Windows 与 LF 检出按统一门槛验包。SQL 清单有 159 份。同一制品另含 1 个 Java 数据迁移，隔离源库及恢复库核实 160 条历史、234 张表和 61 条序列。31 张待补证或定界。 | [#149](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/149)、[#161](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/161)、[#163](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/163)、[#169](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/169)、[#223](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/223)、[#233](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/233)、[#236](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/236)、[#238](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/238)、[#253](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/253)、[#260](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/260) |

### 子任务与待补资料

| 任务编号 | 节点 | 当前状态 | 交付证据或尚待补齐的事项 |
|---|---|---|---|
| BASE-01、BASE-02、BASE-03、BASE-04、BASE-05、BASE-06 | 00 | 已完成 | 基线、PR、代码与数据库、失败线索、开源复用和执行日志均已登记，#175 已合并。 |
| AUTH-01 | 02 | 已合并，待总部岗位验收 | 总部跨品牌只读已落地。用真实总部管理员及财务账号核验。 |
| AUTH-02 | 02 | 已合并，待门店验收 | 同品牌安全地图与公开门店投影已落地。核对两品牌实际门店。 |
| AUTH-03、AUTH-04 | 02 | 已合并，待跨范围业务回归 | 列表、详情、搜索、附件、导出和多租户边界按岗位核对，保留总部与加盟商区别。 |
| AUTH-05 | 02、11 | 已合并，撤权按页面继续回归 | 会话失效、切岗及旧页面需真机核对。培训页面另在 OPS-04 修复。 |
| AUTH-06 | 02 | 地址及测试夹具已合并，待现场验收 | #196/#201 的地址与重新授权已合并。#200、#228 修复组织测试夹具。同一隔离 PostgreSQL 中反向运行 22 类 153 项通过，独立两轮各 17 项通过。上线迁移与现场抽验待办。 |
| CRM-01 | 10 | 客户八步两项业务决定均已合并并验证 | 第 04 步主动填写原因并跳过，计入进度并保留经办人及时间。第 07 步只按正式施工合同签署完成，仅签设计合同继续待办。其余步骤沿用原业务依据。 |
| CRM-02、CRM-03 | 10 | 签约开工、进度区和八步附加资料已合并，待现场复核 | 签约和开工由合同与工程原记录驱动。列表、详情、八步进度和全部步骤已在微信开发者工具核对。八步独立追加文字、照片或附件，保存时登记真实经办人和服务端时间。办理前后均可追加，原有状态、版本及完成条件独立。 |
| CRM-04 | 10 | 同客户端到端已通过隔离配对 | 一件作品，两次咨询产生同一客户档案，用该客户的外部委托版本及自定义付款约定走完八步、施工签约、指定工程接收与实收后开工。已覆盖选本客户报价、上传外部报价、换版重新沟通和转交后重新授权。同时验过原实测单朝向及高差在外部委托和总部历史的回读。 |
| CRM-05 | 10、12 | 历史路径已回归，待历史样本 | 六类在途委托的原号、费用及版本可追溯，实际旧客户证据映射待环境验收。 |
| CRM-06 | 10 | 双端已合并，隔离配对通过 | #221/#222 按上海业务日期提醒。原生页面以 19 位原始客户编号打开跟进页，转交后新负责人收到唯一待办，办结后移除。 |
| CONTENT-01、CONTENT-02 | 04 | 发布登记及链接回填已合并，模板生成封面按最新决定退役中 | 视频在视频号、抖音等外部平台手动发布，回小程序保留发布记录和外部链接。发布回填、链接、重复操作及回执丢失已回归，待手机弱网。 |
| CONTENT-03、CONTENT-04 | 04 | 已合并，待业务验收 | 咨询来源、待关联及任务标题分别接续 #155、#84。旧作品与历史发布见节点 06。 |
| CONTENT-06、CONTENT-06A | 06 | 双端已合并，待业务验收 | #216/#218 接入权威作品编号、版本与可管理标记，兼容旧文本作品。 |
| CONTENT-06B、CONTENT-06C | 06 | 双端已合并，待业务验收 | 验证作者权限、回收及恢复、原任务结果、已有发布可播放并保留客户来源。 |
| CONTENT-05、CONTENT-07 | 07 | 双端统计与文案均已合并，正式归因待定 | #213/#214 按客户去重并分别统计有效签约和净实收。#227 修订标签，51 项页面检查通过。最终成交事件和多触点分配待业务确认。 |
| DESIGN-01 | 08 | 已合并，待资料抽验 | 保留户型检索、资料详情、预览及从灵感库发起快速预算。 |
| DESIGN-02 | 08 | 已合并 | AI 平面、立面出图和候选确认已退出，门店可在客户流程上传外部成果。 |
| DESIGN-03 | 08 | 快速预算已合并并保留，待真机 | 11 月 24 日纪要中取消快速预算的表述已由项目负责人更正。门店、客户、灵感库、旧方案继续使用前期测算、计价、扣点、保存、历史和导出。第 06 步另按正式报价和本次沟通事实验收。 |
| DESIGN-04、DESIGN-05 | 08 | 已合并，待真机复核 | 保留历史图纸和预算结果，旧链接校验角色、项目和版本，核对相册下载和导出。 |
| ENTRUST-01、ENTRUST-02 | 10 | 已合并，端到端配对通过 | 外部成果的客户归属、版本冻结、退回重交及双方可见历史已回归。第 05 步可引用本客户已核对的历史实测单，按原事件和修订冻结需求，固定配对 44 项通过，另验丢回执再转交和旧客户端修改附件标题。 |
| ENTRUST-03、ENTRUST-04 | 10 | 已合并，端到端配对通过 | 核对外部报价、签约和移交，可在没有内部预算或设计行政账号时办理。 |
| ENTRUST-05 | 10 | 已合并，端到端配对通过 | 指定工程岗接收、退回及重交，多次办理仍只建一个工程。 |
| ENTRUST-06 | 10、12 | 代码与隔离测试通过，待历史样本 | 保留在途委托原号、原版本与已进入交接的记录。历史岗位待办及生产迁移待核。 |
| ENG-01 | 03、10 | 同版合同及分期计划移交、部分到账和财务核对已配对 | 工程按同一份施工合同及其付款期次读取已收、余额和条件，业务阶段的实际到账随原合同带入。工程提交金额及凭据，财务在原记录核对。B03 签约即锁版已验收。新旧写入口受同一施工合同约束，签后工程申报和财务核实可继续办理。 |
| ENG-02、ENG-03 | 03 | 已合并，待工程财务合验 | 按各期净收款核验，退款后重新拦截，已约定的交付后尾款单独处理。 |
| ENG-IDEM-01 | 03 | 已合并 | 已确认 #199 合入 backend-main 的 0c8238ee51a4955ea98be1c82c35651f55edf20f，收款指纹含工程、日期、凭证并兼容历史。#202 处理未填日期跨日续办，#209 解决并发重试。#203 的重复工作已关闭。 |
| ENG-IDEM-02、ENG-IDEM-03 | 03 | 已合并 | #204 保留计划批次与原行，#206 复核到货、补发、质检及旧应到量。历史原单无法唯一定位的需业务核对。 |
| ENG-IDEM-04、ENG-IDEM-05 | 03 | 已合并，待工程及财务现场验收 | #207 核对材料更正与流转，#208 处理交付或结算并发和凭证，#210 已将负责人校验覆盖到取得工程锁时。 |
| ENG-04 | 03、09 | 材料流转、分次补发和两岗待办已合并 | #215 保护末批数量及已领用材料。#225/#224 支持领用、损耗、退料、调拨、补发和换货。应到 10、到 5，原补发单再收 2 时仍待 3。#226/#244 验证同单补齐 3 后工程首页消项，老板首页仅留另一张供货单，质量异常独立计算。 |
| ENG-05 | 09 | 财务域回归通过，待岗位验收 | 劳务、材料、差旅等按原单归集。项目净现金、应付余额与毛利分别核对。 |
| ENG-06 | 09 | 质量整改、旧端兼容及施工延期均已合并 | 质量整改支持负责人、期限和处理历史，旧版无字段登记后可在同一原单补派。#242/#243 用原工期识别逾期，记录实际责任人、原因、措施和恢复日。撤岗后拒绝新指派，旧回执可找回。历史原日期缺失按缺失展示。失效深链、回前台和网络丢回执均按原节点复验。 |
| ENG-07 | 03、10 | 已合并，待实际岗位验收 | #153/#154 的负责人交接保留。核对调岗、离岗及历史人员记录。 |
| FIN-01、FIN-02 | 03、09 | 基础已合并，隔离账本已回归 | 分期、部分收付、原凭证及退款净额已核对，真实资金单据待财务复验。 |
| FIN-03、FIN-03A、FIN-03A-CI-01 | 01、09 | 已合并，待财务岗位验收 | 退款/支出退回、作废替代、丢失回执续办均按原记录办理。#171/#195 已入正式 CI。 |
| FIN-04、FIN-05 | 09 | 基础已合并，财务域回归通过 | 核对前期设计费用归属、劳务及材料来源，项目账本保留原始单据。 |
| FIN-06、FIN-07 | 09 | 项目核算双端已合并，待财务实单 | #219/#217 增加当前待付和确认快照。实际用例为当前 85 元、原确认 70 元，旧快照缺字段显示“—”。 |
| FIN-08 | 05、09 | 并发回归通过，待财务岗位验收 | 已复验员工垫付、报销、原单退款及结清的并发处理，旧 #27 的差异已核查。 |
| PERF-01、PERF-02 | 11 | 人工规则已合并，正式公式待 B02 | 业务及工程按有效月份和规则版本保存，由管理层给出底薪、提成基数和冲回条件。 |
| PERF-03 | 11 | 人工核算与回款证据已回归 | 人工绩效可引用实际回款项目与节点，历史版保留原依据。计提与多人分配规则随 B02 确认。 |
| PERF-05 | 11 | 个人周期双端已合并，线下申诉抽验待办 | 支持月度任务归档和生成修订版、人工核算和薪酬历史。#240/#241 接入本人提交/待审更新、其他授权管理人复核和退回重交。旧审批遇新版本返回 409，来源变化会退回，旧版证据仍可查。员工按既有决定在线下提出申诉，后端已有管理岗裁决与调整接口。当前小程序提供提交、复核和版本回查。门店申诉的线下记录与裁决仍需岗位抽验。办理须先配置本人月度目标。 |
| PERF-04 | 11 | 待 B02 | 正式公式、生效版本与退款处理确定后再实现自动核算。 |
| PERF-06 | 11 | 双端已合并，真实原单配对通过 | #229/#230 用同公司、员工、岗位类别和月份关联财务支出原单。应发 100 元、登记 30 元、支出退回 12 元后，实发 18 元、未发 82 元。旧账无关联的保留待核。 |
| OPS-01、OPS-02、OPS-03 | 11 | 已合并，自动化回归通过 | 公告首页及个人入口、置顶、下架和品牌授权已回归。后端公告 11 项通过。 |
| OPS-04 | 11 | 双端验证通过，已合并 | #220 清除撤权或下架后页面保留的旧课件。小程序 47 项、后端 27 项通过，真实接口返回 403/404 后页面均清空。 |
| OPS-05、OPS-06 | 11 | 已合并，待多岗真机验收 | 已回归岗位首页、公告和个人导航。旧通知、返回栈和多岗切换待手机实测。 |
| WALLET-01、WALLET-03 | 11 | 隔离回归通过，真实渠道待验 | 开户、查单和重复回调沿用公司账本。钱包及建池迁移 28 项全部执行，包含原生页面到 Spring 的本地 HTTP 配对。 #248 接续真实登录后回到原充值或公司积分入口，不涉及积分来源选择。 |
| WALLET-02、WALLET-04、WALLET-05 | 11、12 | 公司共享积分和新充值开关已合并，真实资金按用户排期核对 | 超级管理员给门店所属公司的共享池加分。服务端拒绝已关闭的新充值和签名，小程序隐藏入口。旧组织钱包和公司积分订单均能从授权入口查询，已发起订单保留到账核对及退款。 |
| WALLET-06 | 05 | 历史差异核查已完成 | #106/#107 关闭，当前公司额度沿用 #142/#143，旧模拟收银不进入正式渠道。 |
| QA-01 | 12 | 159 份制品迁移清单已合并，31 张表待审 | 清单随 #223/#233/#238/#260 合入。#253 新增附加资料与受控文件票据范围，实际制品 159 份 SQL、234 张表、61 条序列。203 条表归属已登记。27 张需现场证据，4 张按历史归属问题分类核对。品牌、未绑定门店及其地区的处理规则已确认，价目只将真实差异交负责人选择。 |
| QA-02 | 12 | 待 B05 | 待项目负责人提供清理与保留名单和恢复范围。 |
| QA-03 | 12 | 隔离 PG、Redis 和附件恢复通过，现场恢复待排期 | Linux 迁移恢复专项 11 项。干净 PG17 实际顺序执行 159 份正向迁移，核对 234 张表、61 条序列和同公司客户组合外键。两个反向脚本均在写入并发中等待并拒绝撤销已有业务数据。本轮实际还原 160 条 Flyway 历史（159 份 SQL 和 1 个 Java 迁移），财务原单、冻结点和 3 份附件校验通过。现场备份时点与范围待 B05。 |
| QA-04 | 12 | 客户跟进固定配对已验，待目标环境发布 | 已核对本节所列两条功能主线及逐项提交。最终功能组合通过 46 组 519 项后端回归，另有小程序 33 组 1,662 项和 148 项岗位首页检查。目标环境、恢复和试点资料见 B05、B06。 |
| QA-05、QA-06 | 12 | 待真机与 B06 | 需公网 HTTPS 后端及文件接口、微信真机、三家门店账号和岗位。 |
| QA-07 | 12 | 已验证 CI 的迁移恢复与回滚，目标环境待演练 | 冻结和配对选择 21 项通过。固定案例的打包、迁移失败恢复和健康回滚已由 CI 演练。用部署现场备份验证 Flyway 升级、恢复及回滚仍需 B05。 |
| QA-08 | 12 | 客户跟进最终功能组合通过 Windows 验包 | 沿用 #169 和 #236 的 2 MiB 上限、128 KiB 预留及统一统计。18 个包共 9,123,462 字节，主包 1,965,956 字节，剩余 131,196 字节，比预留多 124 字节。 |
| B01 | 10 | 两项业务决定均已确认 | 有原因的主动跳过计入第 04 步进度，保留跳过事实。第 07 步只在正式施工合同已签署后完成。单独签设计合同可归入设计委托档案，施工签约待办。 |
| B02、B04 | 11 | 两项规则暂缓 | 后续由项目负责人提供正式薪酬公式及公共费用分摊口径。 |
| B03 | 03、10 | 签约即锁定付款计划已确认，双端已合并验收 | 签约前由业务与客户确认期次及条件，签约时锁定同版。签后所有岗位只读期数、金额、日期和工程节点。工程申报实际收款，财务照常核对入账。 |
| B05、B06 | 12 | 隔离恢复已验，实际资料与排期待项目负责人 | 品牌、未绑定门店与覆盖地区按已确认规则处理。实际价目差异、现场备份和恢复范围待补。用户负责部署，三家门店试点账号、岗位和时间由用户后续安排。 |

### 本轮代码与配对验收

| 节点及功能 | 对应 PR | 后端提交 | 小程序提交 | 同一版本的验收与合并记录 |
|---|---|---|---|---|
| 01 财务更正 CI | [#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) | `196944dd5e2e49644e244e794e08683d95eaa154` | `41136df0162ea221ff2ba12b4f61c4da91a78c3e` | 专项与配对通过，两端为当时核验主线。 |
| 02 公开地址 | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196) | `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58` | `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` | 合并至 `11014a5cf936166b71134bf40f838ad93b04ce43`，换址另见 #201。 |
| 02 人员导入测试夹具 | [#228](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/228) | `fef28033b76539cfa2411d035e7159074f37db63` | `2a65b0ed2cea3a95a29c29e939038652dc157455` | 员工目录和导入使用相同表，入驻测试清理自己创建的角色关联。22 个测试类共 153 项均通过，无跳过。 #228 已合并至 `6c07bcbd5e38db124ad85a4b71a783b18ceb6a40`。 |
| 03 并发收款 | [#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209) | `af5c7eed6b71bda10c762bc03293154aab5ec569` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | 后端 CI 限流重跑通过，配对通过。合并至 `4c6419632dc36cc1a294b3f8d3a19068845ca70a`。 |
| 03 交付授权 | [#210](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/210) | `50157e2d68f7c585776724c18a6c2512ad81f269` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | 加锁时复核项目、负责人及组织。保留旧指纹兼容并按需校验新凭证。实际 PostgreSQL 三组 56 项通过。 #210 已合并至 `f1b13e5859acd3b141bab33c32bb265814da7d3f`。 |
| 06 个人作品 | [#216](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/216)、[#218](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/218) | `30c07c558e025955832106a9a9ec6a1af804a3d9` | `f6625ecc23d2d317261b362b44f9b5b6070f89a3` | 真实数据库验证回收后旧发布可回放，新发布会被拒绝。 后端合并至 `3c171c40a8256fd5307d3b47408a2d4d16f46652`。 小程序合并至 `4ab766ca1b7b767c57920f044b57c3bf303a1596`。 |
| 07 内容统计 | [#213](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/213)、[#214](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/214) | `715431ed719ddaab2eb156b2a8faa70bc25ccac2` | `b1a9f27531442678b40719e3977d69c68a1bfed5` | 真实页面显示 2 条发布、6 次咨询、3 名去重客户、1 名签约、0 名净实收。与节点 06 联合编译，4 项交叉回归通过。 后端合并至 `323e6106a61ef52bf8b45dd595025e43fa1e9df6`。 #214 已合并至 `5e2616a3ee5eeb36c099a972d168e23ca8001ed2`。 |
| 07 咨询客户的口径 | [#227](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/227) | `323e6106a61ef52bf8b45dd595025e43fa1e9df6` | `2d0fc7c1f73df0eef9880bcc40d81b4715b8b9de` | 单独的页面 51 项通过，明确客户曾有关联咨询。与工资、整改、包体分支试合时完整 npm 回归通过。 #227 已合并至 `a754c4e9634ba37117fd15e605899a6ac7a4266e`。 |
| 08 快速预算 | [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211)、[#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) | `90625c8c03e4578cc3ba203fef302a79b99cb2c7` | `8377058cdfa98ad1c8501144556da63ab08523c2` | 后端合并 `f5be78e6e93dc173f4a238fd167dcde7f286ebc3`，小程序合并 `b5a5bd237f2358988ca648777047eb26dec1c57d`。 门店、客户、灵感库、旧方案均可进入预算，独立预算和项目预算可按商家价格计价、扣点、保存、重试与查历史，含导出。后端专项 252 项和小程序专项 189 项通过。 |
| 09 材料数量 | [#215](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/215) | `d2d74cc4c69c4f5facb6b41a68d5c09abb5aec7e` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | 已接续 #206 合并版本。74 项工程回归通过。 #215 已合并至 `4b7b20558f17be1b0316ef2f3b151051d40a70c7`。 |
| 09 项目待付 | [#219](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/219)、[#217](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/217) | `921f72ff1e4545577a8e464d9107862473ba82a7` | `424915543ebc24c1063809139c2f1dd3f3767d43` | 劳务应付 100 元，支付 30 元后确认，再退回 15 元。当前 85 元和已确认 70 元同时可查。 两端合并至 `56ffe131109902935a68d22b71034c21fef63731` 和 `e877e5198c193534def650dcba3e57df1c949c05`。 |
| 09 材料流转 | [#225](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/225)、[#224](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/224) | `88ff3437289e57ca89d5fbc51e058e4bf1eef817` | `6955de6614ad3d63af5709fdc1c368d7752ec671` | 实收 3、领用 0.5、待结退料 0.25、可分配 2.25、待补 1。原单与应付各自可追溯。修复主包曾超出的 557 字节，同一限额下余 131,364 字节。 #225 已合并至 `9d505421fcd70900033e954bad9dc26e1689cfcc`。 #224 已合并至 `cd9e485bf2fb65007b16b302525e01d6627a7ac6`。 |
| 09 分次补发 | [#226](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/226) | `f294ffce81528da64be0503d128b54684ac09e52` | `6955de6614ad3d63af5709fdc1c368d7752ec671` | 最新原单汇总专项在 `6c96ae6b355fb590a5c6997230a920801c8fb82d` 执行工程 59 项通过。再与交付、整改和薪酬分支重新组成同一后端，工程、财务、组织与运营九个测试类共 112 项通过。 #226 已合并至 `18497179e0fc0b6a89022de3c7589679626052b4`。 |
| 09 整改单及待办 | [#231](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/231)、[#232](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/232) | `4f8379b4f41e10d1e441a9fa3d84276791e7ecd3` | `d260de21658532c3929a9d7a83c05fb3bfea4a39` | 工程 81 项、经营 11 项、前端集成 355 项、WXML 130 项。单号贯穿六步处理。独立 PG 复核撤岗接管、无人可办时组织重派、并发回退保存原分派记录，实际页面复核中断重试和旧回包。 #231 已合并至 `45fc53b6838ed9d190df8f5576026dd1889071b7`。 #232 已合并至 `bb6184ea48cdf98a878b119461d0c1c33fa187ea`。 |
| 09 已安装版本登记施工整改 | [#237](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/237) | `addeba425b071febb933c8ba83ca431d5f16aeb0` | `cd9e485bf2fb65007b16b302525e01d6627a7ac6` | 旧页面产生的真实命令只有 action、nodeId、note、title。丢回执后发送相同幂等键，真实 Spring MVC 与 PostgreSQL 登记一张待分派原单。新端补负责人和期限后重试仍回原号。工程及回退 10 项、上述跨端用例另 1 项通过。 #237 已合并至 `c9a2c968e8d668bc44f2a2b39104c6a8f1206967`。 |
| 09 施工延期及处理历史 | [#242](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/242)、[#243](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/243) | `6eb4d0e6b40e7a6a025ec9908da7ad6dd2ef4677` | `24163266e7299f541fcdf62eafdaf4b000ad987f` | 同一节点基准 11 月 23 日、现排 11 月 28 日，11 月 26 日仍逾期 3 天。现场选择实际责任人和 11 月 30 日，丢回执再试只记一条原因及措施。已验证撤岗后拒绝新指派、旧回执可找回、失效深链不会打开别单，原工期与现排期分开展示。后端工程 87 项、经营 13 项，小程序专项 12 项、集成 367 项通过。 #242 已合并至 `be8225583888fa1d0b5c1e3a6fdd14a9c5b06313`。 #243 已合并至 `a334e42d88b1a1d229a146b6cb1116f5e44d291b`。 |
| 09 工程及老板首页的待到货 | [#244](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/244) | `9483d14743dae77584563f586375f7079c74d376` | `fd4520ee5fe90aa178f16831f34fd0011fbb8099` | 在已有后端 PR 上接续。按顺序执行 157 份正向 SQL 后，用真实 Spring MVC 和 PostgreSQL 分两轮完成 24 次、32 次查询与 6 组原生页面核验。应到 10，实际到货 5→7→10，工程首页待到货 1→1→0，老板首页 2→2→1。两岗均能回到同一份 19 位原单，工程可按幂等键补余下 3 件，老板只读。跨公司、项目、工程人员范围及独立质检均已核对。后端原有专项 61 项、小程序 43 项通过。 #244 已合并至 `7212ab804a593d21bda0af6d324362ab0f1c3c7a`。 |
| 10 客户待办 | [#221](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/221)、[#222](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/222) | `eb9896dda690273a683c7bbaccd7c8da5b2a944f` | `9840b81ed73411ace359f9d65dac7b199d889343` | 实际 PostgreSQL、Spring HTTP 及原生页面验证提醒、转交、重约、办结。另行联验两、三、五期签约退回重交至开工。 #221 已合并至 `0366e851d431bdc3a53fd77b39e8318d3fc6a94f`。 #222 已合并至 `9c1d746ff7c14ba4dccd0bddb65b448a91d16c4c`。 |
| 10 八步进度区 | [#249](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/249) | `be8225583888fa1d0b5c1e3a6fdd14a9c5b06313` | `c820b9af6cf361a005156d055094ba45a52a59be` | 微信开发者工具用生产 Page/WXML 逐步查看八步。用户指定的第 02 步卡片及八项展开列表已按相同像素尺寸与正式实现比对。打开合同及开工原单、原位返回、非连续完成、切岗与未提交草稿均有回归。客户流程 42 项、131 个 WXML 和完整 npm 检查通过。 #249 已合并至 `db1de8de10af6922ee1ef5eb8c24bc5b15ec4378`。 |
| 04、10 内容咨询到实际开工 | [#252](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/252) | `a7c4640a224ca0e25d2a5788131b5b796b769346` | `8276671c7c5f6025277fe1b96722a0941f1c71c3` | 真实作品保存、发布登记、回填外部链接和客资创建后，以第二次咨询关联同一旧档。单一客户走满八步、正式施工合同、指定工程、实收约定款和实际开工。原始 AI 结果及外部链接为隔离样本。在 Windows 和 PG17 运行完整移交套件及固定小程序的 18 项，全部通过且零跳过。开工后再次逐行核对两笔原咨询的来源、作品、发布链接。 #252 合并提交 `bac56d5836521db499a103ba830a09b95d2f9423`。 |
| 10 第 06 步正式报价沟通 | [#250](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/250)、[#251](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/251) | `35e7eeec42ab40b095d55f3597843fe7cda27f30` | `7163de4e7a2f7f2a50c34c72e3efccd600c5db8c` | 接续侧聊原提交。可选本客户正式报价原单或上传外部正式报价，第一版 ¥12,345.00、第二版 ¥67,890.00 均以原单金额和版本写入报价沟通记录。换原件后清空旧沟通结果及日期并再次确认，保留六次历史。原生、Spring MVC 与 PG 在正式报价沟通、外部文件、转交及导出三组场景分别完成 21、10、15 次 HTTP 请求。样本为两张原单和一份外部 PDF。独立复核转交后原经办人失去选单、原单及导出权限，新经办人仍可办理。38 项后端专项及 2 项真实配对通过，小程序 33 组 1,622 项。 #250 已合并至 `5c2e2e1cef525bd8dbb5dfde3bdfc6ebffe3c067`。 #251 已合并至 `6917e1ea5a679882bb5c575da35e986782b80fa2`。 |
| 10 旧版小程序保护已关联正式报价 | [#262](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/262) | `c9801f6f769f58151355978ba535eaf64fe1b4bd` | `6917e1ea5a679882bb5c575da35e986782b80fa2` | 用报价上线前的小程序 `8276671c7c5f6025277fe1b96722a0941f1c71c3` 和现有版本分别保存。旧版本未提供报价编号时提示更新，原单、金额、备注、版本和历史均保留。新版本明确改用外部报价时清除既有沟通和日期，可上传外部 PDF 再次确认。丢回执原键重试只留一条历史。原头三类 PG 35 项，额外新旧包真实配对 1 项。 #262 已合并至 `4c17ad0c10b19bd169a624c53c78d3a4c1165e11`。 |
| 10 八步附加资料 | [#253](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/253)、[#254](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/254) | `9c25fdd46183ac335bfbb7372b9d3feeb2ae8839` | `d7c1373835d799f10db39bd312cc44f9f357815c` | 文字、图片、附件按同客户和实际步骤分别追加，登记上传人及服务端时间，能回查。源单、八步状态及修订独立。后端专项 20 项，前端 49 项、132 个 WXML 通过。组合验证三次取件逐字核对 PDF/PNG，覆盖未完成、已完成、未收到回执、跨客户和切岗。 微信开发者工具本地实际运行组件，30 笔请求，7 条路径通过。正式报价和附加资料合在同一代码树执行 42 项 PostgreSQL 测试，均通过。 #253 已合并至 `bec686f0a60c43a4cd90926d7a025148ca560ac0`。 #254 已合并至 `8276671c7c5f6025277fe1b96722a0941f1c71c3`。 |
| 10 定制方向的客户确认 | [#255](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/255)、[#256](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/256) | `ca055e3b4e9e3e883e847f5669da111724fa8af5` | `fb006b6edd23371b0456d3d0a85ab70fe420e5e1` | 同一客户的标准户型、外部定制方向或旧外部文件分别往返 4、17、8 次。定制方向可用文字，改方向须重新确认，总部能读取当前与历史原文。前端 33 组 1,633 项另加岗位 148 项。仓内固定配对已实际调用客户、其报价和登录人资料接口，覆盖附加资料的三次提交、两条记录、三次取原件。 合入旧包报价保护后的组合检查 37 项，零跳过。 #255 合并提交 `dbbb47af8949fcf238af7dd8d2a849b92d591611`。 #256 合并提交 `3b1dc12bd2e7570811f85e95f2c00d009f1161ca`。 |
| 10 引用本客户的历史实测单 | [#258](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/258)、[#259](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/259) | `ca11db9194e568a925af731e6cd81cd28a4b7c42` | `4c7b73fe77138d30365796eee2b6f345ce3d4a8e` | 可选本客户历史已核对实测版本，冻结日期、经办人、地址、尺寸和原附件。跨客户、未核对、已撤回文件均被拒。原始空名或超长的附件在委托端生成兼容旧版的展示名，仍可改备注和逐字取原件。客户转交后，旧经办人丢回执返回页面会重新核权，获准时同键重试，失权时清资料。配对 PG/MVC 44 项、原生委托 31 项通过。 最终工作树的 CRM、附加资料、外部委托和工程移交共 57 项通过，零跳过。 #258 合并提交 `5f5b176bf646939b008353fdd9234573654c9a21`。 #259 合并提交 `a6ecd09f8567662c99701eb28e2ed23325f35379`。 |
| 10 邀约、到店与带看录入 | [#257](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/257) | `399da49b7fc48e0874dcddda459cd0b4d31b9c54` | `58c82af23bfa2db04c85c230086a82b5fea97f0b` | 邀约快捷时间需经办人选定并确认。首次办理到店讲解可带入本客户需求和当前经办人，带看只可选本客户已完成记录中的工地。本次反馈均可编辑，原有记录继续按实际发生日显示。47 项专项，微信开发者工具 6 组交互、7 次读取、0 次自动写入。 新头在原生 390 像素视口逐步复核，每步各一个「附加资料」。第 03、06 步的原单凭据也可分别识别，补记只产生一次资料写入。 第 02 步预填逐一对照服务器当前本人编号、姓名以及客户编号和需求原文。 #257 合并提交 `8fc4d182388ebfc5633546368674f757adc8cf71`。 |
| 10 工程开工原单的现场核对 | [#263](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/263) | `4c17ad0c10b19bd169a624c53c78d3a4c1165e11` | `42c6131653b2c0d5e2070773af61dd2ca8e1a0ff` | 四通一平、业主确认情况和项目经理确认情况均可如实记录，保存在原工程 START 单，沿用实际开工日、经办人、凭据、工期和资金校验。请求在途切后台再返回，仍冻结原单和请求键。丢回执原键原体重试只生成一张原单和一条事件。原生页、Spring MVC 和 PG 20 次实际读取，付款阶段快照逐行一致，原 PDF 字节一致，进度由 7/8 更新至 8/8。旧工程只展示原有事实。 #263 合并提交 `c4e0ed7330fc511178101b3e94c7d1b5d1fb40ff`。 |
| 10 实测朝向与现场高差 | [#264](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/264)、[#265](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/265) | `edb8f305ac93844ba1eaae13c810aafd6b3d1d76` | `c94462413f062a8da18ce6788a95084893e48590` | 在原测量记录填写真实朝向、高差或影响设计的说明，沿用既有事件和委托快照，可在当前、历史及总部授权视图回查。旧客户端省略字段会保留既有值，显式清除可区分。同一客户 25 次原生、Spring MVC、PostgreSQL 往返覆盖重新核对、引用新版本、重放、客户转交、成果与交接，交接时仍为 7/8。 主动引用已核对的同客户实测单时可留存所选 SAVE 事件的原办理说明，含换行。旧程序省略字段或自动回传同版编号会保留旧委托内容，较晚的测量和独立附加资料另存。小程序 `bc3c0ebda6ccb29cc0c8966ecbd1f89b92b4fa9c` 配本行后端，83 项 PG 回归全部通过，另在微信开发者工具核对总部接口返回的三份真实响应。吸收 #263 和 #266 后，小程序完整检查 1,654 项及岗位首页 148 项通过。 #264 合并提交 `629f75d7142ee2c3fa42db014bcbe5a80ab07a9c`。 #265 合并提交 `41d4fae6b5cecf28ffbfd8a26abcf4783c662079`。 |
| 10 小程序主动切回标准户型 | [#266](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/266) | `5f5b176bf646939b008353fdd9234573654c9a21` | `262d5373b8d9224cf4da539c2a162124a6593ed4` | 主动切回标准户型时明确清除定制方向、原确认及日期。普通编辑及重试沿用原有处理。 #266 合并提交 `00cf2eafb456dd40f2763a6a836db2310a317810`。 |
| 10 旧版小程序保护已确认的定制方向 | [#267](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/267) | `543e897fe726c60b2dba3143aa1ef4414a03ecc8` | `c27aad362d2d806815eb901e6fdb0eaad9d60a16` | 旧版小程序遗漏选择方式时提示更新，保留定制方向、确认、备注、进度及历史。新版可主动改选，丢回执按原键续办。另以旧版小程序 `8276671c7c5f6025277fe1b96722a0941f1c71c3` 及 #266 的 `262d5373b8d9224cf4da539c2a162124a6593ed4` 连到本任务的后端提交 `880d62b534b683bd28759bcdf2a6a74b848224d1`。合入实测单改动后的最终配对见本行。两版共 11 次 Spring MVC、PostgreSQL 交互。旧包只改备注被拒后原数据不变。新版主动切换、同键重试、再次确认、查看原方向与备注均通过，历史共 4 条。该配对与原有 CRM 回归共 14 项通过，零跳过。部署时先让兼容小程序可用，再启用后端保护。 #267 合并提交 `133a3a1e0a24380857036b1cf0fd2562adcf1a35`。 |
| 10 统一「客户跟进」页面名称 | [#268](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/268) | `5f5b176bf646939b008353fdd9234573654c9a21` | `ddcb8220be4474cf96ed7e12d5653240cded7c72` | 列表、办理页、工作流入口和总部只读详情统一命名。八项办理内容、进度及打开原单的行为沿用。微信开发者工具 390 像素视口核对办理页、总部详情与空态、客户列表四种按钮及工作流入口。进度轨与展开列表各有八项，可在第 08 与第 02 步间切换。办理和总部页面回放 Spring/PostgreSQL 响应，其他入口使用本地样例。两份整改文档随同一 PR 更新。 #268 合并提交 `fdfab70fe4ac1ce51c1e898c8cee171bcc61a85c`。 |
| 10 施工签约与设计合同归档 | [#270](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/270)、[#271](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/271) | `c7b91109e3364fecfad6b4f3f68d81c37e4b6bc6` | `78b747503602b1f6485190e7e1c7a65bf048bd1f` | 已签设计合同的原件可在设计委托回查。其后签署正式施工合同才完成第 07 步，原施工合同编号及附件与销售原单一致。固定配对 44 项后端、小程序 1,655 项及岗位 148 项全部通过。 #270 合并提交 `d6c807ff57fd5d5ca90ec27ac7b840d225079239`。 #271 合并提交 `5e4349de6b105b0036a02f4dec11e5d4deeeba37`。 |
| 10 免带看及施工签约统一验收 | [#272](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/272)、[#273](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/273) | `9f1fbd4b5ba71b183a33b34147d458e64ec37e22` | `780029b481dcdd37b425586c163701f5e1c105b6` | 两个版本包含施工签约和跳过带看。七类后端共 81 项通过，含 7 次原生请求只记一次跳过，及设计合同与施工签约正反两种事实。小程序 1,657 项、岗位 148 项通过。微信开发者工具实测跳过记录和随后追加的附加资料。 #272 合并提交 `5333e1dfa719685ab6c0f0fafdea168f39d0bcdf`。 #273 合并提交 `76352a76eb465afa5fbafaec2b30299aa1723afe`。 |
| 03、10 施工分期与工程收款 | [#274](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/274)、[#275](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/275) | `c4def6dc05a2d349319395e59eb07cbf7846ae72` | `598ec656e46824a2e9a98e9286cb0cd8c355f801` | 首期应收 400 元、实收 250 元、余款 150 元，第二期应收 600 元。工程按当前移交版本查看条件与凭据，财务退回可补件、重复确认只入账一次。付款专项 165 项、独立复验 80 项及原生专项 83 项均通过，详见上方合同实例。 #274 合并提交 `f256266edd376123655d98ac0b1d7553456fa891`。 #275 合并提交 `cf1b8838f17044ffd7e04fdc5f74a2ade88f0c19`。 |
| 03、10 签约时锁定付款计划 | [#277](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/277)、[#278](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/278) | `9a12abad7ad3209dcfe46842c413effae424e76e` | `ccda8b419153e5901c71b931358c82fbea855302` | 固定双端的 PostgreSQL 专项 11 类 169 项通过，覆盖签约和调整计划两种并发先后、历史已签合同、旧命令重试及财务退款。原生小程序 1,662 项、岗位首页 148 项、付款专项 38 项通过。独立 PostgreSQL 联测 2 项验证了新旧入口均拒绝签后改约，工程部分收款、补件和财务核实继续办理。旧版小程序 598ec656e46824a2e9a98e9286cb0cd8c355f801 连接新后端，签约移交至开工和工程收款至财务核实另有 2 项通过。微信开发者工具在 390 像素视口回放旧服务返回的可编辑标志，已签原单显示锁定，仍能查分期和历史。签前模拟保存丢回执，签后重试只读原合同，全程只有签前的一次写请求。 后端合并提交 `f356e76e2b7498474526706b765090abfad195e8`，小程序合并提交 `45a28811d8affbd02b4ea6da12adb5e0d53be94b`。 |
| 03、04、10 同一客户从发布咨询到开工后收款 | [#281](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/281) | `86be41247d164ac825b8576859c482160dcaf599` | `6d193dbbc72c813e2def8cce6a3a6f6a71d6e78d` | 同一原生客户端配真实 Spring 服务和 PostgreSQL。专项 1/1，含该专项的整类 31/31，零失败、错误、跳过。原单、金额和复跑方式见「同一脱敏客户验收与隔离恢复演练」。#281 合并提交 `2a1f5d0e07a7af1a8e2a898f62542921ee6c28ba`，变更仅涉及后端验收测试。 |
| 11 公司共享积分与线上充值开关 | [#245](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/245)、[#246](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/246) | `95dac66fe58cfe287ad90acafb6d670673b2e0e8` | `433d5f454ab6903466c2cec8c4dfd2ff15e8e4fd` | 默认关闭新充值，开关启用后恢复已有支付链。经授权的岗位钱包列表及管理台运营列表均返回旧组织钱包单和公司积分单，岗位钱包详情按两个原单号回查。后台 103 项、管理台 45 项、前端 1,653 项及岗位 148 项通过。 #245 合并提交 `30962e412ab1795a426c5c1502a52d06157ee58e`。 #246 合并提交 `afcd231ad962cd5af69419fa9ba8214be1933281`。 |
| 11 学习中心 | [#220](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/220) | `4e5acd3c83936843c2f05f9b555b51873e6a0e22` | `ae41b60b51cd8f4501b8fff52c8f4d4cc97c73a6` | 用同一课程的真实响应验证可见、撤权及下架。 #220 已合并至 `2a65b0ed2cea3a95a29c29e939038652dc157455`。 |
| 11 工资实付 | [#229](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/229)、[#230](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/230) | `b4db0ab44bc7a99669bfff9209618fec7ff6150a` | `baa770343db49e7b7e1e9dc27475bfc7ff20e1cd` | 工资实付登记和支出退回都引用原单。100 元应发，30 元付款，退回 12 元，实付 18 元、未发 82 元。实际小程序发出的命令和 Spring 受理一致，重试仅保留一笔。含 #235 的同一提交上薪酬 16 项、运营 96 项通过。 #229 已合并至 `04e641f6a410ebf5bdcfbcc50e08f22da57aa41a`。 #230 已合并至 `962d6cdb7d0f9397c228115b2e55cdaca9a43fc3`。 |
| 11 运营测试夹具 | [#235](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/235) | `1a4ab5e7ec07cbe14aef0ce73c766321934a1381` | `cd9e485bf2fb65007b16b302525e01d6627a7ac6` | 总部、运营、公告和培训五个 PostgreSQL 集成测试类共用同一份用户表。复现总部先行时 update_time 缺失，在完整运营模块正、反序分别通过 96 项。 #235 已合并至 `d26456227985052127db1fd87c717189b5ce9e29`。 |
| 11 个人绩效周期复核 | [#241](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/241)、[#240](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/240) | `eed520cd390f82d4c5c317b0ad1c5619d0ad2f79` | `efcd868c55741fe5b45df62ef4405661dc2d6963` | 实际小程序与 Spring MVC/PostgreSQL 在三岗位配对 13 次读取、8 次写入。730 元的 17 位合同与客户编号准确。签约日从 11 月 3 日改到 4 日时退回，旧版仍保留 3 日。本人待审重提后旧复核返回 409。完整绩效模块 90 项、页面专项 14 项通过。 切岗时清理当前及历史详情、原单和延迟回执。在此头完整 npm 检查 33 组、1,600 项通过。 #241 已合并至 `5789a41658602d7de770453bcff51e82ce69be83`。 #240 已合并至 `52b28a8eeead81f7d4bace2c9c1873fedc9bcf38`。 |
| 11 公司积分及充值的登录回跳 | [#248](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/248) | `be8225583888fa1d0b5c1e3a6fdd14a9c5b06313` | `39b2b4da52eb0ff32261cc2157e8f694e8e95411` | 从现主线既有充值和日后可能采用的公司积分页返回登录，认证和岗位核对完成后回到发起页面。实际 Spring HTTP/PG 套餐读取只触发一次 GET，未生成订单。19 项登录回归和完整 npm 检查通过。已通过双项 CI 并合入。该修复保留现有充值功能。 #248 已合并至 `334396d12a447efbe642b0e0acc1b1a9f6d11a92`。 |
| 11 公司积分发放与充值入口（首轮设计） | [#245](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/245)、[#246](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/246) | `5b219ad6e8c3f9d825096e9e525d02f78abbfb41` | `46daa2372550fc8799af497ab0f35626a1c5337e` | 此对提交记录此前方案的回归基线。管理台 19 项、小程序 21 项和后端 33 项通过，含历史回调只入账一次。项目负责人已确认保留充值代码并默认关闭，原 PR 已按默认关闭、允许开启的口径修订。实际交付见「公司共享积分与线上充值开关」一行。 #245 合并提交 `30962e412ab1795a426c5c1502a52d06157ee58e`。 #246 合并提交 `afcd231ad962cd5af69419fa9ba8214be1933281`。 |
| 12 离线清单 | [#223](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/223) | `f3032fd35e56766ee552259e7bd3f3a5539dd358` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | Windows 所有权 25 项、Linux 11＋21＋25 项通过。旧清单准确报 MIGRATION_SET_CHANGED。与 #233 合用，尚缺 31 张表时准确报 TABLE_COVERAGE_CHANGED。 #223 已合并至 `3881c5f82f58d58e653483b6b5dfa083d786cd39`。 |
| 12 迁移归属数据 | [#233](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/233) | `f683f0b6990c66927525041658e02b5c63b350b0` | `2a65b0ed2cea3a95a29c29e939038652dc157455` | 制品与源码 155 份逐字一致。与 #223 工具组合后 233 表、61 序列相比独立 SQL 语法树遗漏和多报均为 0，31 张表继续待审。 #233 已合并至 `1e4db126d37eb0a5bec6680ae1a58a4f9d109a5a`。 |
| 12 Windows 主包与配对检查 | [#236](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/236) | `9d505421fcd70900033e954bad9dc26e1689cfcc` | `1bad1351a348f67768ea34d4a60f5f83ba4cb830` | Windows 默认检出曾只余 118,432 字节。固定 9 类小程序文本的换行，修复对配对工作流的文本断言。两个干净检出的主包均 1,965,497 字节、余 131,655 字节，完整 npm 回归通过。独立比对 1,121 个小程序文件（含 359 张图片）及 6 个设计来源样本，均与原 LF 版逐字节相同。 #236 已合并至 `b9a087fcbd7f27fd1484d90f3c3aad45323c3a32`。 |
| 12 两份新迁移 | [#238](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/238) | `a0337245a460eaaf1eb79e6cb93a1539ba1a5c24` | `cd9e485bf2fb65007b16b302525e01d6627a7ac6` | 加入已合入业务主线的两份 SQL 及四张表的来源，已有 155 份资源完整保留。新制品 157 份正向 SQL 的路径和原始字节全部比对一致。 #238 已合并至 `01bc2389e0ecf1088ef6a05b7d2c95b68969f282`。 |
| 12 八步附加资料迁移归属 | [#260](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/260) | `db71faf15e23bd58e1fcbfe077c1d98b0e990423` | `8276671c7c5f6025277fe1b96722a0941f1c71c3` | 隔离制品及干净 PG17 对照 159 份迁移、234 张表、61 条序列。离线归属工具 25 项通过，源记录留存原来的 157 条，再增加 2 条迁移、1 张客户归属表。31 张待现场取证或定界。 #260 已合并至 `a0a8507d5e623d7e76e2303e9f77ebc1b3805097`。 |

### 迁移清单的逐项核验

在后端提交 `323e6106a61ef52bf8b45dd595025e43fa1e9df6` 构建服务端 JAR，逐字核对八个 Flyway 路径中的 155 份正向 SQL。制品 SHA-256 为 `47edd1edd66a41105cb8e04b4b19acc9e66150be22399e8cdc140e5de329e043`。历史清单的 70 份文件及摘要全部相符。旧扫描器在 #206 对应版本找到 147 份，到 #216 增至 148 份。补入实际打包的经营模块后，这两个时点分别为 154 和 155 份。

在 85 份增量中，54 份已有归属规则，28 份需核对实际资金、作业、对象或权限，3 份需业务确定范围。74 张新增表里已有 43 张可按明确条件登记，27 张需专项证据，4 张需定界。旧有 159 张加上新增 43 张，共 202/233 张已有选择条件。

<details>
<summary>查看 85 份新增迁移及逐份依据</summary>

| 固定源码中的迁移 | 处理 | 依据或待办 |
|---|---|---|
| [V20261114.911](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261114.911__org_point_pool.sql) | 取证 | 公司成员额度及权限可按组织和人员继承。账户和追加流水沿既有资金模型逐笔核对可用、预留及在途。 |
| [V20261114.912](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261114.912__design_billing_binding.sql) | 取证 | 设计创建时冻结 origin 及计费对象。与对应 B 公司池或原执行方的一笔业务匹配后继承。 |
| [V20261114.913](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261114.913__design_prompt_receipt.sql) | 取证 | 回执没有组织字段。由 job_id、attempt、operation 匹配办理人。UNKNOWN 与已派发表明需保留在途事实。 |
| [V20261114.914](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261114.914__org_point_subject_price.sql) | 定界 | 全局科目价缺组织维度，登记生效与历史版本，确认有效公司池价和目标现有配置冲突。 |
| [V20261114.915](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-design/src/main/resources/db/migration/franchise-design-remote/V20261114.915__local_public_case_favorites.sql) | 承接 | 员工收藏以租户、org、员工过滤，external_case_id 对 A 发布库仅为引用。 |
| [V20261114.916](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261114.916__fr_content_pool_biz_key.sql) | 取证 | 内容任务已有 company_id。余额预扣与恢复租约需对账，菜单及角色种子沿既有 B 权限闭合。 |
| [V20261114.917](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261114.917__org_point_headquarters_admin.sql) | 取证 | 调点按公司、双人复核和账本串联，价格审计随已定界的价格主档。总部菜单仅注册。 |
| [V20261114.918](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261114.918__org_point_hq_price_idempotency.sql) | 取证 | 实际价格发布者、请求键和 price_id 关联总部价格审计。复用既有请求结果。 |
| [V20261114.920](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261114.920__design_reference_asset.sql) | 承接 | 原 B 文件表扩展参考图片类别。沿已登记 fr_asset 映射，校验用途、字节和组织。 |
| [V20261115.710](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261115.710__pf_franchise_account.sql) | 取证 | 账号的组织和别名有明确父项。密码策略仅 tenant/user。与共享账号及认证责任核对。 |
| [V20261115.711](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261115.711__pf_org_admin_audit.sql) | 承接 | 组织管理审计按同租户组织、actor 及不可改历史办理单处理。 |
| [V20261115.712](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261115.712__pf_org_directory_menu.sql) | 承接 | 目录权限是 system_menu 指定种子，沿 B 菜单父链凭业务键和主键核对。 |
| [V20261115.713](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261115.713__pf_org_account_reset_audit.sql) | 承接 | 扩展已定界的同一组织审计动作，保留原始重置事实。 |
| [V20261115.917](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261115.917__fr_content_digital_clones.sql) | 取证 | 数字人/声音涉及同意版本、源素材、服务商资源、计费和恢复状态，逐项核原授权与在途。 |
| [V20261115.981](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-commission/src/main/resources/db/migration/franchise/V20261115.981__commission_fee_modes_pricing.sql) | 承接 | 按设计组织和版本登记定价政策。委托报价冻结在原 submission，跨组织关系按既有委托规则。 |
| [V20261115.982](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-commission/src/main/resources/db/migration/franchise/V20261115.982__commission_dispatch_manifest.sql) | 承接 | 用原 commission_id 和 submission_version 继承组织和设计方，冻结清单中的三类成果文件。 |
| [V20261115.983](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261115.983__design_fee_sources.sql) | 承接 | 应付、付款和收款按原组织账本选择器。设计费用以委托阶段、版本和原单对账。 |
| [V20261115.984](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261115.984__design_deliverables_scopes.sql) | 承接 | 资产类型沿原 B 文件映射。下载授权按作用域重新签发，留存原文件与版本。 |
| [V20261115.985](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-sales/src/main/resources/db/migration/franchise/V20261115.985__quote_budget_basis.sql) | 承接 | 销售报价增加预算依据，沿 fr_sale_quote 归属和原客户。有版本才承接冻结事实。 |
| [V20261116.730](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261116.730__platform_operations.sql) | 取证 | 资料和能力按同租户组织，审计闭合 actor。外部待核作业须保留原任务证据和单一执行者。 |
| [V20261116.731](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261116.731__training_phase_one.sql) | 取证 | 课程及素材按总部渠道、发布版本与对象授权取证。上传中的文件、租约与分片须核恢复方，短期播放票据可重新签发。 |
| [V20261116.732](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261116.732__training_legacy_catalog_drafts.sql) | 承接 | 内置六门旧课程及 38 课时按原迁移仅作为草稿。手工完成状态不构成实际学习。 |
| [V20261116.733](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261116.733__training_material_management.sql) | 承接 | 经审定的资料继承原租户及目录，保留修订和软删除时间、经办人。 |
| [V20261116.923](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261116.923__fr_content_persona_ip_profile.sql) | 承接 | B 公司人物资料沿原 persona。明确来源的已有表达特色按迁移本身保留。 |
| [V20261116.924](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261116.924__fr_content_hifly_direct_upload.sql) | 取证 | 服务商文件 ID、有效期和来源需对用户许可及实际接收状态。账号、人物归属由 company_id 取得。 |
| [V20261116.925](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261116.925__training_material_categories.sql) | 取证 | 七类内置种子可重建。自定义及已回收分类需凭原资料和总部运营记录确认。 |
| [V20261116.926](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261116.926__content_clone_defaults.sql) | 承接 | 按公司和人物取得默认头像/声音，引用之前已确认归属的 clone。 |
| [V20261116.927](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-org/src/main/resources/db/migration/franchise-system/V20261116.927__content_persona_self_permission.sql) | 承接 | 针对本人 IP 的功能与角色种子，按已有 system_menu、system_role_menu 的 B 角色及父链核验。 |
| [V20261116.928](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-crm/src/main/resources/db/migration/franchise/V20261116.928__crm_customer_address.sql) | 承接 | 客资和客户地址快照按各自 company_id 继承，保持原关联关系。 |
| [V20261116.941](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-workflow/src/main/resources/db/migration/pf/V20261116.941__business_message_delivery.sql) | 取证 | 全局单例控制派发起点，不能按组织筛选。需保存旧消息边界并确定新消费者的激活时间。 |
| [V20261116.942](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-commission/src/main/resources/db/migration/franchise/V20261116.942__commission_simulated_payment.sql) | 取证 | 历史模拟委托按原委托版本回读。有无真实现金以对应财务凭据为准，账务保留历史标志。 |
| [V20261117.943](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261117.943__finance_workbench.sql) | 承接 | 账本识别同公司下的客户、委托和工程。原收支、退款和应付保留独立归属。 |
| [V20261117.944](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-sales/src/main/resources/db/migration/franchise/V20261117.944__sale_finance_confirmation.sql) | 承接 | 申报对应原合同阶段、原财务收款、申请人及凭证。保留待核实和退回状态。 |
| [V20261117.945](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261117.945__content_persona_drafts.sql) | 承接 | 草稿按公司和实际工作人员归属，保留未发布和发布版本关联。 |
| [V20261117.946](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-workflow/src/main/resources/db/migration/pf/V20261117.946__message_center_context.sql) | 取证 | 单例激活时间与没有组织字段的已派发 event_id 需对旧消息去重、待发送队列及切换水位。 |
| [V20261117.960](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261117.960__persona_publication_version.sql) | 承接 | 人物和草稿增版本列，按原行范围。旧草稿中无法推定的发布来源保持空。 |
| [V20261117.961](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261117.961__content_result_delivery.sql) | 取证 | 结果交付会影响恢复任务。已产脚本按同公司原任务核对，缺结果和重复脚本单列核实。 |
| [V20261117.962](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261117.962__video_draft_scope_revision.sql) | 承接 | 原视频草稿增加组织和修订约束，按原创建者及草稿引用闭合。 |
| [V20261118.950](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-engineering/src/main/resources/db/migration/franchise/V20261118.950__fr_eng_progress_camera.sql) | 取证 | 工程节点沿原 ID。摄像头按工程归属，但播放地址受限。目标授权与 SQL 中现有工程 CRUD 权限须复核。 |
| [V20261118.963](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261118.963__content_material_library.sql) | 承接 | 按公司、原资产及父素材映射素材库，保留可见性、所有者和回收事实。 |
| [V20261118.964](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-workflow/src/main/resources/db/migration/pf/V20261118.964__message_read_snapshots.sql) | 承接 | 临时批量已读快照在目标重新生成。实际一条消息的持久已读事实由 pf_message 承接。 |
| [V20261118.965](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261118.965__close_void_advance_reimbursements.sql) | 承接 | 以原公司、应付、已作废垫付报销事实闭合相应旧账。保留原历史财务记录。 |
| [V20261118.966](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-sales/src/main/resources/db/migration/franchise/V20261118.966__sale_stage_as_of.sql) | 承接 | 变更前的分期条款由 stage_id、合同版本、公司及生效日确定。 |
| [V20261118.971](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-crm/src/main/resources/db/migration/franchise/V20261118.971__home_business_facts.sql) | 承接 | 跟进和方案沿客户组织。保持历史普通跟进、实际到访及确认预算的不同证据。 |
| [V20261118.972](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261118.972__content_result_delivery.sql) | 取证 | 非文本任务的既有产出决定是否交付完成，核原服务商/恢复状态，防重复生成。 |
| [V20261118.974](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-engineering/src/main/resources/db/migration/franchise/V20261118.974__site_rectification.sql) | 承接 | 整改单按项目组织，历史事件由 issue_id 继承真实整改单与原办理人。 |
| [V20261118.986](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-engineering/src/main/resources/db/migration/franchise/V20261118.986__engineering_owner_lookup.sql) | 承接 | 原项目负责人索引调整，组织和工程负责人的选择器已有定义。 |
| [V20261118.987](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261118.987__hifly_file_upload.sql) | 取证 | 上传接口增文件字段，继承原人物与服务商上传的归属、有效期和凭证要求。 |
| [V20261118.988](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261118.988__franchise_store_map.sql) | 定界 | 固定地区重建，门店位置按现有账号，品牌按获准门店或目录引用闭合。未关联品牌由总部核对。品牌 identity 序列需登记及校验水位。 |
| [V20261118.989](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261118.989__franchise_district_centers.sql) | 承接 | 地区中心点使用 SQL 自带数据集校验值，可从固定版本重建。该表不含门店客户记录。 |
| [V20261118.990](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261118.990__isolated_simulated_package.sql) | 取证 | 测试购买与财务复核是独立事实。按源流水、原余额、经办人及证据编号逐笔核。 |
| [V20261118.991](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261118.991__transfer_reviewed_simulated_points.sql) | 取证 | SQL 将经复核历史测试权益从正式账拆出，含余额改写和补偿流水。未决预留、缺审批或重复应先逐笔对账。 |
| [V20261118.992](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261118.992__payable_settlement_status.sql) | 取证 | 付款、收回、退款与应付状态由同公司事实计算。迁移含触发器与历史重算，需源/目标逐单净额对账。 |
| [V20261118.993](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-engineering/src/main/resources/db/migration/franchise/V20261118.993__link_verified_manual_contracts.sql) | 取证 | 手工工程与正式合同在真实客户、姓名电话、唯一工程、收款及各期条款一致时才能承接。遗留歧义由业务确认。 |
| [V20261118.994](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261118.994__company_recharge.sql) | 取证 | 历史充值和公司充值目标并存，逐笔按原渠道订单、credit_target、实际入账账户和回调/退款判定。 |
| [V20261122.990](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261122.990__pf_initial_password_expiry.sql) | 取证 | 包含动态 SQL 修改共享 system_users 的临时密码状态。仅对确属 B、仍需首次变更的用户执行，已自设密码和跨应用身份逐项核。 |
| [V20261123.996](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-performance/src/main/resources/db/migration/franchise/V20261123.996__fr_perf_reconciliation_snapshot.sql) | 承接 | 绩效确认快照沿原公司和人员归属，保留历史确认时数值。 |
| [V20261123.1021](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261123.1021__normalized_clone_asset_import.sql) | 取证 | 新增服务端核验来源资产字段。旧服务商直传、未结束上传和新归档需核实际资源及授权。 |
| [V20261123.1022](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261123.1022__content_task_personal_dismiss.sql) | 承接 | 作者和任务的个人隐藏记录按同公司校验。原生成任务和计费状态继续各自保存。 |
| [V20261124.991](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261124.991__franchise_directory.sql) | 定界 | 已绑定门店由获准组织闭合。未绑定账号、辖区未解析、跨品牌或仅来自工作簿的覆盖记录需总部明确保留范围。 |
| [V20261124.1022](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261124.1022__publication_platform_identity.sql) | 承接 | 发布账号唯一键增加平台维度。按原业务发布归属保留不同平台同名账号。 |
| [V20261124.1023](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261124.1023__content_publisher_accounts.sql) | 承接 | 发布账户按公司、所有人、平台及标识。既有发布回连该账户，保留历史禁用情况。 |
| [V20261124.1025](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261124.1025__content_cover_requests.sql) | 取证 | 封面已归档可按公司和文件处理。处理、结果未知或待归档状态须核服务商回执及单一恢复方。 |
| [V20261125.1031](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261125.1031__platform_announcements.sql) | 取证 | 总部公告按实际受众及原 B 主体闭合，品牌和审计有父表。核历史共享租户和实际发布身份。 |
| [V20261125.1032](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-org/src/main/resources/db/migration/franchise/V20261125.1032__hq_admin_announcement_post.sql) | 承接 | 总部管理员的岗位模板是 B 权限种子。原在途岗位与历史授权按现有 B_ADMIN 闭合。 |
| [V20261125.1033](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-org/src/main/resources/db/migration/franchise-system/V20261125.1033__announcement_permissions.sql) | 承接 | 公告权限只在已审定 B 菜单/角色关系中处理，源与目标相同 ID 同时对权限码、父项和角色。 |
| [V20261125.1034](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/yudao-platform-operations/src/main/resources/db/migration/pf/V20261125.1034__announcement_create_request.sql) | 承接 | 沿原公告租户、创建人及 creation request 标识保留原回执，不补造已发布状态。 |
| [V20261125.1040](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-crm/src/main/resources/db/migration/franchise/V20261125.1040__customer_eight_step_workflow.sql) | 承接 | 八步流程、步骤、事件按公司/租户与客户关联，原字段及 JSON 附件随步骤/版本核，不能从旧状态推算材料齐备。 |
| [V20261125.1041](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261125.1041__business_document_assets.sql) | 承接 | 业务文件沿 fr_asset 的审定路径，核用途、组织和已验字节。 |
| [V20261125.1042](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-crm/src/main/resources/db/migration/franchise/V20261125.1042__customer_workflow_asset_lookup.sql) | 承接 | 客户步骤和历史事件的附件索引沿原有客户/组织及资产作用域。 |
| [V20261125.1043](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261125.1043__workflow_download_scope.sql) | 承接 | 票据绑定客户和步骤，新环境按已有授权规则重新领票。 |
| [V20261125.1044](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-sales/src/main/resources/db/migration/franchise/V20261125.1044__contract_evidence_history.sql) | 承接 | 合同更正的前后凭据与原公司、合同、操作者版本闭合。禁止用历史 SIGNED 填造凭据。 |
| [V20261125.1045](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261125.1045__franchise_public_map_name.sql) | 承接 | 公开名沿已定界目录。保留明确审核字段和隐私约束，不从内部成员姓名推导。 |
| [V20261125.1046](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261125.1046__contract_download_scope.sql) | 承接 | 合同下载作用域绑定原合同，短时票据按既有重新签发约定。 |
| [V20261125.1047](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261125.1047__fr_project_accounting.sql) | 承接 | 项目结算快照沿组织、工程和确认人。原始现金、应付、历史快照分项保留。 |
| [V20261125.1048](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261125.1048__fr_payroll_ports.sql) | 承接 | 薪资规则与人工记录由公司、人员、月份、版本确定。实际支付仍跟原付款，迁移旧规则无需补造工资公式。 |
| [V20261125.1050](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-sales/src/main/resources/db/migration/franchise/V20261125.1050__flexible_payment_plan.sql) | 承接 | 合同付款计划版本及各阶段原 ID/日期保持，按同公司合同和原收退款关联。 |
| [V20261125.1051](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-engineering/src/main/resources/db/migration/franchise/V20261125.1051__engineering_payment_plan.sql) | 承接 | 施工计划与收费约定保留原合同快照、阶段 ID 和工程关系。 |
| [V20261125.1052](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-sales/src/main/resources/db/migration/franchise/V20261125.1052__customer_payment_plan_draft.sql) | 承接 | 客户付款计划草案及历史按同客户和版本，正式条款以实际合同来源验证。 |
| [V20261125.1060](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-commission/src/main/resources/db/migration/franchise/V20261125.1060__external_commission.sql) | 承接 | 外部委托请求及结果按公司、客户、原委托和版本回读。引用原审批、付款计划和历史文件。 |
| [V20261125.1061](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-commission/src/main/resources/db/migration/franchise/V20261125.1061__external_handover.sql) | 承接 | 交接包沿同公司委托，核冻结成果、接收人、现场确认和付款条款。 |
| [V20261125.1062](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261125.1062__external_handover_download_scope.sql) | 承接 | 按外部交接包历史槽位和版本授权，重新签发目标下载票据。 |
| [V20261125.1100](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-wallet/src/main/resources/db/migration/platform-wallet/V20261125.1100__org_point_pool_backfill.sql) | 承接 | 零余额开户审计具 org、actor 和请求键。已存在的原命令用于核对，不将记录回放成划款。 |
| [V20261126.1001](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/resources/db/migration/pf/V20261126.1001__franchise_public_map_address.sql) | 承接 | 按已定界目录保留授权地址、授权人和时间。未授权时 SQL 要求公开地址和授权字段为空。已有记录可直接依约承接。 |
| [V20261126.1002](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/323e6106a61ef52bf8b45dd595025e43fa1e9df6/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-content/src/main/resources/db/migration/franchise/V20261126.1002__personal_work_trash.sql) | 承接 | 在公司作品原表补回收时间和非负的作品库修订号，沿用原作品选择器。 |

</details>

#### B05 的积分价目例外与已确认的品牌、门店处理规则

本次在固定后端逐份比对 159 份正向迁移及其摘要，得到 234 张表、61 条序列。运行时另执行 1 个 Java 数据迁移。234 张表扣除已有归属条件的 203 张，余下表与下方 4 张定界、27 张取证逐张一致。另有 25 项离线归属校验通过，覆盖余额缺失、重复归属、在途款项和孤立附件引用。

现场按同一核对时点提供源、目标脱敏差异，注明时区、租户及版本或导入批次。尚未收到现场对照，具体冲突价目、品牌、门店及其数量待盘点后列出。积分价目只有发现冲突时才请负责人选择版本。项目负责人已确认品牌、未绑定门店和服务区按证据判定去向，只有仍有歧义的个案才单列复核。比对为零时登记范围、时间和零行结果即可。

| 事项与状态 | 涉及的表 | 现场先提供 | 负责人答复方式 |
|---|---|---|---|
| 待确认 · 积分价目冲突时采用哪版 | `org_point_subject_price` | 财务列源、目标的科目、单价、计价单位、最低计费量、ACTIVE 版本、生效或停用时间及价格来源。无 ACTIVE 行时核对程序默认价 | 对冲突科目选定依据和版本，核实原生效时间、注明保留的历史版本。已有流水保留原计价依据。 |
| 已确认处理原则 · 加盟商品牌归属 | `pf_map_brand` | 总部运营列出品牌编号、名称、已关联门店及目录，标出无门店引用或跨范围的记录 | 按租户及品牌编号核对。能证明属于众墅之家的纳入 V3，已有明确原系统归属的留在原系统，证据不足的暂缓并保留原记录。 |
| 已确认处理原则 · 未绑定门店及其服务区 | `pf_franchise_directory`、`pf_franchise_directory_region` | 运营列出未绑定账号、跨品牌或仅在导入表内出现的门店，附来源行和覆盖地区 | 可核实属于众墅之家的门店及已确认服务区一同随迁。地区随同租户的父门店。证据不足时保留原记录，只把仍有歧义的门店或区县列入例外清单。 |

原「模板生成封面」供内容创作人员在准备发布作品时制作封面，项目负责人已确认取消该功能。视频在外部平台手动发布，小程序保留发布记录及链接回填。历史封面请求 `fr_content_cover_request` 按原请求和服务商回执取证。现有公司积分目录未单列封面生成。核价使用「文案改写」「数字人口播」「快速预算」等实际科目。加盟商品牌记录用于总部加盟商名录、门店地图的品牌筛选和覆盖统计，也供总部公告按品牌选择受众。

如实际发现价目冲突，可答复「科目 P01 采用目标库 V3，源库 V2 留档」（虚构示例）。品牌、未绑定门店及服务区按上述已确认原则处理，现场仅提交证据仍不能定界的个案。

27 张取证表由六组岗位按下表提供凭据。逐表字段与核对条件在下方明细中。

| 取证分工 | 表数 | 现场提供的资料 |
|---|---:|---|
| 财务与积分 | 7 | 财务、积分运营提供对账时点的公司余额/预留、流水与业务单、调点审批、价格请求与审计、模拟权益复核凭据。价格请求/审计关联已确认的价格版本。 |
| 历史设计与委托 | 3 | 设计服务及委托经办提供原命令与计费操作、尝试次数、回执状态、原委托版本。已派发或结果未知时核原执行方和接手人，模拟单单独核对。 |
| 内容与工程媒体 | 4 | 内容运营、服务商和工程提供生成回执、人物授权、文件编号/哈希、在途状态及摄像头目标权限验证结果。登记证据编号即可。 |
| 消息与后台作业 | 4 | 运维提供激活时间、最后处理事件/投递位置、去重记录、未终结作业和唯一处理方。 |
| 账号与公告 | 2 | 总部管理员提供账号与租户归属、初始密码策略的状态或账号退役记录，以及公告发布人、受众品牌和状态。 |
| 培训 | 7 | 培训运营与存储负责人提供课程/草稿、运营授权、课件版本与对象摘要、内置/自定分类、分片及办理审计、在途处理方。 |

交接时保存原单号、版本和凭据编号。上述 27 张表只有出现证据冲突时才需对应负责人裁定。

<details>
<summary>查看 31 张待审表和各自所需证据</summary>

| 表 | 待办 | 验收条件 |
|---|---|---|
| `design_billing_binding` | 取证 | 用 origin、org_id、ref_type/ref_id、subject 与 operation_id 交叉匹配实际执行及结算方。 |
| `design_prompt_receipt` | 取证 | 无 org_id。先用 job_id、attempt_no、operation_id 对回已审定设计命令。UNKNOWN 或已派发记录跟踪原执行方。 |
| `fr_commission_simulated_order` | 取证 | company_id、design_org_id、委托及原版本。作为历史模拟记录保留。真实结算需独立原凭据。 |
| `fr_content_cover_request` | 取证 | 按公司、作者、原请求键及摘要核对历史封面申请。已应用封面沿发布原单查。处理中、结果未知或待归档的记录先核原有回执和归档对象，现场取证无需再生成封面。 |
| `fr_content_digital_clone` | 取证 | 按公司、人物、作者与同意版本闭合人脸/声音源。核服务商资源、归属和委托处理许可，在途任务核单一执行方。 |
| `fr_content_hifly_upload` | 取证 | company_id、人物、服务商 file_id、状态、有效期与哈希闭合。已签发或正在处理的上传核终态。 |
| `fr_eng_camera` | 取证 | company_id 与 project_id 闭合。stream_url 在源码列注释标为受限播放地址。需确认目标访问授权及有效地址。 |
| `org_point_account` | 取证 | org_id 对审定 B_ORG。余额与不可变流水、在途预留、审定旧账号闭合后才能确定期初。 |
| `org_point_hq_adjustment` | 取证 | 按 org_id、请求、maker/checker 与流水对账。未完成审批需原经办责任及单一执行方。 |
| `org_point_hq_price_request` | 取证 | tenant_id、actor_id、price_id、request_key/hash 还原价格请求。先确认总部配置对象，对同键已有结果去重。 |
| `org_point_ledger` | 取证 | org_id、operation_id、biz_key、业务引用与原资金映射核对。保留预留、结算、释放和历史价格，防双计。 |
| `org_point_price_audit` | 取证 | 由 price_id、subject 和总部 actor 回连已定界价格及历史调整。保留原始审计。 |
| `org_point_simulated_purchase` | 取证 | tenant_id、org_id、原 biz_key 和 receipt。区分测试权益、已复核划转与正式资金流水。 |
| `org_point_simulation_review` | 取证 | source_ledger_id 回连原币池，保留复核时余额/流水数、财务签认与 evidence_reference。待证部分隔离。 |
| `org_point_subject_price` | 定界 | 全局 subject、version，没有组织字段。价目是 B 侧配置。源、目标同版本内容冲突或旧数据归属混用时，由总部确认保留的历史及有效版本。 |
| `pf_announcement` | 取证 | 总部 B 公告按受众 ALL 或 BRANDS、租户、原发布人和状态处理。跨范围共享租户确认 B 发布主体及受众。 |
| `pf_business_message_activation` | 取证 | 单例激活时间。登记旧/新消息处理边界与唯一派发者，按切换边界在目标重建。 |
| `pf_franchise_directory` | 定界 | 已绑定账号按 tenant_id、org_id、account_id 确认。没有账号/组织的导入记录及未解析覆盖区须按来源表和总部品牌名单决定。 |
| `pf_franchise_directory_region` | 定界 | 按同租户 record_id 继承已确认目录归属，再核实际地区 code。未定界的父记录带入例外清单。 |
| `pf_map_brand` | 定界 | 按 tenant_id、已审定 B 门店及目录引用闭合。总部运营提供目录和门店引用证据，按已确认的源、目标规则归属，证据不足的保留原记录并登记例外。 |
| `pf_message_center_activation` | 取证 | 无组织的激活时间。保留旧消息读态，核实切换水位后建立目标激活边界。 |
| `pf_message_delivery_checkpoint` | 取证 | 无 org_id。event_id 追溯消息来源及旧目标投递/去重记录。登记唯一派发者、切换水位。 |
| `pf_staff_password_policy` | 取证 | 没有 org_id。按已确认 B_ADMIN 及其租户，核对共享 A/B 用户和首次密码状态。保留既有退役边界。 |
| `pf_task_reconciliation` | 取证 | 按公司和 task_key 追溯原作业、evidence、operator。OPEN 或等待服务商的记录需要唯一办理人。 |
| `pf_training_audit` | 取证 | 没有组织外键。以对象类型/action、object_id、租户和 actor 闭合课程或资料审计。 |
| `pf_training_course` | 取证 | 按运营人员、已批准的租户课程、引用课件和版本建立闭包。未发布草稿与已发布版分项。 |
| `pf_training_material` | 取证 | 仅有 tenant 和创建人。按 B 总部运营人员、被课程引用的材料及授权核实，包括未引用草稿。 |
| `pf_training_material_category` | 取证 | 系统默认分类可按 Git 种子重建。保留经确认 B 资料或运营员使用的自定义分类及删除记录。 |
| `pf_training_material_version` | 取证 | 经已审定 material_id/tenant、version、hash、size、原件/预览/媒体/封面对象映射。在途处理核租约。 |
| `pf_training_upload` | 取证 | 按素材版本、实际上传人、request_key、分片状态及原作业，确认目标唯一处理方。 |
| `pf_training_upload_part` | 取证 | 按审核过的 upload_id 继承归属，核分片序号、大小和 SHA 对象，避免孤立分片。 |

</details>

在隔离 PostgreSQL 17 内执行原始建表，逐项核对 7 条原清单遗漏的隐式序列及其所属列后回滚。涉及 `fr_design_remote_command.command_id`、`pf_handover_pending.id`、`pf_handover_audit.id`、`pf_map_brand.id`、`pf_wallet_recovery_task.id`、`pf_training_audit.id`、`pf_announcement_audit.id`。正式旧库的序列所有者、当前值、调用状态和最大已用 ID 须由目标环境确认。

### 工资与整改合并后的两份迁移

155 份历史版本固定在 `323e6106a61ef52bf8b45dd595025e43fa1e9df6`。该版本的实际 JAR 为 190,156,903 字节，SHA-256 为 `47edd1edd66a41105cb8e04b4b19acc9e66150be22399e8cdc140e5de329e043`。

后端 `45fc53b6838ed9d190df8f5576026dd1889071b7` 合入工程整改单，`04e641f6a410ebf5bdcfbcc50e08f22da57aa41a` 随后合入工资实付。以第二个合并提交的干净工作树打包，实际 yudao-platform-server.jar 为 190,172,104 字节，SHA-256 为 `f04b845317a9daf2606e0624935b09d193f6173ad26ba32043b3da2ada7cd4e2`。八个 Flyway 位置的 157 份正向 SQL 对应 Git 原始字节，原 155 份资源路径、内嵌 JAR 来源与摘要完全相同，缺失、多余、摘要错误均为零。按资源路径排序的索引 SHA-256 为 `3ff95b8e1c59f3a4dba135367184f9e5cdf132990fc6476e47d70f68b507c6a7`。测试夹具和旧融合服务不在这些运行模块中。

| 迁移 | 归属和变动 | 正向 SHA-256 | 反向 SHA-256 |
|---|---|---|---|
| [V20261126.1006 工程整改](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/04e641f6a410ebf5bdcfbcc50e08f22da57aa41a/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-engineering/src/main/resources/db/migration/franchise/V20261126.1006__engineering_rectification_assignment.sql) | 在按公司、工程、节点归属的主单和按 issue_id 归属的历史事件中增加可空的负责人和期限，记录事件顺序索引。当前或历史行已记录任何一个值时须保全后再回退。 | `7684234009596c5fa06cc75e745bea8645f01d05ce5e26ec0f67773a455a08fe` | `fd25187a12fd1e46a285c619202e124f95683964a520e5c115475b4b4d946848` |
| [V20261126.1070 工资实付来源](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/04e641f6a410ebf5bdcfbcc50e08f22da57aa41a/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-finance/src/main/resources/db/migration/franchise/V20261126.1070__fr_payroll_payment_source.sql) | 在公司应付原单上记录可空工资原单，通过公司和原单联合外键限定本公司。保留尚未关联的老应付，已有人为核账的工资关联必须保全。 | `f173da75aa973b4e723aadf5f79f4cfa39b366686febf138f13d33364d0b2f43` | `74a08ceb0ecf19e66babb24c72c457cfc9af1f7603dbcf0b40d3efeaf608e8d3` |

两份均为结构变更及索引，新增表和序列各零，所有表的所有权、迁移范围与静态筛选沿用原判定。完整 AST 和工具覆盖集合一致，233 张表、61 条序列，已登记的 202 张表和 31 张待审表均未变。真实旧库目录与生产验证状态仍为 false。旧清单检查报 MIGRATION_SET_CHANGED，本次增量配合已合并解析器后，plan 和合成样例检查通过，带真实源码检查报 TABLE_COVERAGE_CHANGED（退出 2）。

PostgreSQL 17.11 以固定提交的原始 SQL 验证了整改主单、历史事件单独记录责任人或期限的四种拒退、空数据正常回退再应用和错误事务隔离级别的拒退，共六种情况。另开两条并发写入与回退会话分别验证工程历史期限和工资关联，数据库实测回退等待业务事务提交后仍会拒绝删列，原记录保持。

### 工程和绩效合入时的功能版本与验证

小程序 `a334e42d88b1a1d229a146b6cb1116f5e44d291b` 与后端 `be8225583888fa1d0b5c1e3a6fdd14a9c5b06313` 为本次核对的两条功能主线。两条主线的完整 Git 文件树分别等于本地跑过组合回归的版本。施工延期的前后端 PR 均固定对端提交，其常规 CI 与配对发布演练全部通过。配对记录可从[后端演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45777127272)和[小程序演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45777125655)回查。

组合后端在隔离 PostgreSQL 运行 19 个测试类，共 198 项，全部通过，零跳过。小程序完整 `npm run check` 的 33 组 Node 测试共 1,608 项通过，额外岗位首页检查 148 项通过。按仓库资源检查脚本核算 18 个包，合计 9,040,915 字节，主包 1,966,017 字节。现场版本、真机、迁移及岗位验收见下表。

### 八步附加资料的两份迁移

以附加资料后端提交 `9c25fdd46183ac335bfbb7372b9d3feeb2ae8839` 构建的实际服务 JAR 共 190,196,326 字节，SHA-256 为 `3819ee9c60ed657ecc1875aa0c33d2c733a0d04134b87357215ddce654c0c9e4`。八个 Flyway 位置合计 159 份正向 SQL，原 157 份的路径、内嵌来源和字节摘要一致。按资源路径排序的索引 SHA-256 为 `2b08fb6fec5591669617299f92c51f756e6eb7087b9f47d69756c03ee8c06cf1`。

| 版本 | 归属与回退条件 | 正向 SHA-256 | 反向 SHA-256 |
|---|---|---|---|
| [V20261126.1102 客户附加资料](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/9c25fdd46183ac335bfbb7372b9d3feeb2ae8839/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-crm/src/main/resources/db/migration/franchise/V20261126.1102__customer_step_supplements.sql)、[反向脚本](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/9c25fdd46183ac335bfbb7372b9d3feeb2ae8839/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-crm/src/main/resources/db/migration-undo/franchise/V20261126.1102__undo_customer_step_supplements.sql) | 按租户、门店、客户、步骤保存追加内容，经办人及任职、服务端时间和原件引用均可追溯。已存记录时拒绝删表。 | `8bb0b3cb1da45a655b66d1f7ef4cf8643521a5033580fe9a3970f45cbf18986b` | `acedd8fd3a012afcd07efc01a186f3978e09eb35f5c6eea071c42a9f5a9ca02b` |
| [V20261126.1103 资料票据](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/9c25fdd46183ac335bfbb7372b9d3feeb2ae8839/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration/franchise-asset/V20261126.1103__customer_supplement_ticket_scope.sql)、[反向脚本](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/9c25fdd46183ac335bfbb7372b9d3feeb2ae8839/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/domains/franchise/franchise-asset/src/main/resources/db/migration-undo/franchise-asset/V20261126.1103__undo_customer_supplement_ticket_scope.sql) | 沿用受控文件票据，扩展合同、工程步骤的资料归属。原件和业务资料已引用时拒绝缩减范围。 | `093c6354637adedad9e4b47efcfdce0730c85a9b82dce3844f465d38c5e24256` | `e1c06c393d1132a614002dfde0aa3d6555975606ee3c9e79fcdbfa119a8b749c` |

隔离 PostgreSQL 17 执行全部 159 份脚本，实表为 234 张、序列 61 条，客户组合外键包含客户与门店编号。逐表逐序列与制品对照无差异。新增的 `fr_customer_step_supplement` 按已选客户筛取。可登记 203 条表归属，31 条现场取证及定界事项继续保留。两份反向脚本在空库可执行。并发有真实记录时等待提交再拒绝回退，记录、经办人和附件引用均保留。

### 客户跟进的合并与配对记录

本次在小程序与后端现有主线上接续侧聊代码。原生进度区合入 #249，八步独立附加资料合入 #253/#254，客户正式报价合入 #250/#251，#262 补齐旧版小程序保存备注时对原单关联的保护。附加资料由后端登记真实办理人、任职和时间。微信开发者工具使用隔离数据，实测合同、开工源单与选填附件各自办理。客户转交后撤权、外部 PDF 原件、报价换版、第 03 步定制方向和第 05 步历史实测单已在对应固定提交上实测。具有写权限的经办人确认实际业务事实后，办理第 01 至 06 步。施工合同与实际开工由源单写入第 07、08 步。

同一件内容由两笔咨询关联同一客户，完整跑到签署施工合同、指定工程负责人、工程接收及实际开工。开工后由工程反查施工合同、客户，仍可读到两笔咨询原先的作品和发布记录。外部发布链接使用隔离样本。实际外部设计继续由经办人在对应客户委托下手动上传成果、施工图与专业审核依据。正式图纸和报价自动对接所需的软件方资料列在下表。

### 客户跟进的八个步骤

小程序的客户列表按钮、办理页标题、工作流入口及总部只读详情统一显示「客户跟进」。页内继续显示八项业务步骤和原有完成进度。文案提交 `5b8c40f3ab0b29392978b84c860284b89d628e35`，开发时核对的后端版本为 `5f5b176bf646939b008353fdd9234573654c9a21`。小程序完整检查 33 组 1,644 项通过，岗位首页 148 项通过，18 个包通过验包。

| 步骤 | 办理人操作及完成依据 | 当前验收 |
|---|---|---|
| 01 邀约 | 选择渠道与实际约定的日期、时间，再登记客户已答应到店。发生日期默认上海当日，可按事实修改。约定到店时间由经办人选择。 | 已回归已存日期、快捷未来时点、人工确认及旧草稿。#257 已合并。 |
| 02 到店 | 登记客户实际到店讲解，带出可核实的接待人和本客户需求。完成时记录本次讲解反馈，可点选快捷结果或自行填写。 | 已回归跨客户、切岗及延迟回复，均不覆盖已录入内容。#257 已合并。 |
| 03 户型或定制 | 选择标准户型或记录已确定的定制方向，登记客户确认。已有外部方案附件可沿用，定制意向可直接用文字。 | 已回归无附件的定制、换方向后重新确认、标准户型及旧外部文件，总部可查两版方向。#255/#256 已合并。 |
| 04 带看 | 实际带看填写工地与客户反馈。免带看由经办人填写原因后主动跳过。 | 实际带看可直接录入或选择本客户历史工地。免带看用独立操作填写原因，进度计入已办。办理页、历史及总部能区分实际带看和已跳过，可查经办人和时间。旧版小程序已包含 1/8 的跳过进度，该步显示「待核对」并只读。新版显示「已跳过」及原原因。跨门店共用的工地名册仍待提供授权范围。 |
| 05 实测 | 核对实际测量单。外部委托可引用本客户已核对的某一版事件，或继续手动上传合格原件。 | 实测朝向和影响设计的现场高差可随本版保存。固定来源、原件字节、跨客户拒绝、旧版本再次编辑及客户转交后返回页面均有回归。#258/#259、#264/#265 已合并。主动引用时可逐字回看原测量说明。 |
| 06 正式报价 | 选择本客户原单或上传外部正式报价，登记本次真实沟通。原单金额与版本自动读取，换版后重新确认。 | #250/#251 已合并，原件、查看、下载、换版和转交后的读权限已验证。快速预算保留全部前期测算功能。 |
| 07 施工签约 | 正式施工合同已签署后，按签署原单自动完成。仅签设计合同可在设计委托档案留存。 | 同一客户先归档已签设计服务合同，第 07 步待办，施工合同原单数为零。签署正式施工合同后该步完成、第 08 步待办。已验证从设计委托仍可下载原设计合同。施工签约同时锁定该版付款计划。 |
| 08 实际开工 | 工程接收同一客户的外部成果版本、客户确认凭据、交底说明和付款约定，核对开工准备及应收，登记实际开工日和凭证。 | 已验证退回重交、单次建项和实际开工回写。#263 已在原工程开工单留存四通一平、业主和项目经理的实际核对情况。回执丢失时使用同一键和原始内容续办。 |

八步都可在办理中及完成后另添文字、拍照或选图、上传附件，按步骤保留真实上传人和时间。第 07、08 步保留合同、工程原单入口。

### 八步最终功能版本与配对验收

小程序 `45a28811d8affbd02b4ea6da12adb5e0d53be94b` 配对后端 `f356e76e2b7498474526706b765090abfad195e8`。核对两条主线的已跟踪代码、测试及打包配置，均与本次通过验收的组合一致。各任务的 PR、配对提交和合并提交见上表。

- 后端 46 个测试类共 519 项，全部通过，零跳过。覆盖内容与客户归属、报价与合同、分期及实收、人员权限、资料受控下载、工程及财务回归。同时运行原生小程序与 Spring MVC、PostgreSQL 的移交及八步资料配对。
- 小程序完整检查 33 组 1,662 项通过，另有 148 项岗位首页检查通过。共 18 个包，主包 1,965,956 字节。微信开发者工具已核对八步展开与切换、补记资料和总部办理历史。原生页面与 Spring MVC、PostgreSQL 的配对测试验证了旧单引用、转交撤权、开工回执丢失后的续办和工程原件回看。

### 同一脱敏客户验收与隔离恢复演练

#### 同一位客户从咨询到开工后收款

在隔离 PostgreSQL 中，首笔咨询新建客户 `2125939095699435522`，第二笔咨询关联到同一档案。两笔咨询均能反查作品 `2125939091689680898` 和发布记录 `2125939092339798017`。本次全程只有一份施工合同和一个工程。固定配对为小程序 `6d193dbbc72c813e2def8cce6a3a6f6a71d6e78d`、后端 `86be41247d164ac825b8576859c482160dcaf599`。[后端 PR #281](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/281)

| 核对环节 | 同一客户的原单及实测结果 |
|---|---|
| 客户跟进 01–06 | 按邀约、到店、客户确认选型、实际带看和实测核对依次办理。第 06 步单独上传外部正式报价附件 `2125939097269227520`，登记已沟通，业务原记录为 V1、1,000 元。 |
| 签约与工程接收 | 同一客户的委托 `2125939106852089858` 移交已签施工合同 `2125939121905446914` 和该版付款计划。工程为 `2125939126796005377`。签约后从新旧两个入口改约都被拒绝，第 07 步由签署原单完成。 |
| 开工前收款与门槛 | 首期约定 400 元。签前预收 100 元带入，工程另报 150 元，财务退回补件后沿用原流水核实一次。实收 250 元时开工被拦。工程再报 150 元、财务核实后首期收齐，登记 2026-11-26 开工。重试只留一条开工记录，进度为 8/8。 |
| 开工后收款 | 第 2 个工程节点 `2125939126926028803` 验收后，工程申报二期 200 元，财务核实。业务和工程同见首期 400/400 元、二期 200/600 元。财务实际有 4 笔，合计 600 元，合同余款 400 元。 |

财务原单按本次业务顺序为 `2125939121519570945`（100 元）、`2125939132563173378`（150 元）、`2125939139131453442`（150 元）、`2125939142348484610`（200 元）。这些编号来自专项的第一次完整运行。整类 31 项再次用新编号走通相同结果，零失败、错误和跳过。

本例实际执行小程序原生脚本、Spring MVC 或业务服务及 PostgreSQL。签约、交接、首轮申报和财务核实走原生脚本与 HTTP 控制器，其余办理走业务服务。外部内容结果、发布链接、岗位身份及上传原件均为合成资料。实地带看、第三方渠道真实发布、真机及实际资金待门店现场核验。

重复验收时先在固定小程序目录执行 `npm ci`。从固定后端的 `平台程序` 目录用 Maven Wrapper 运行 `-pl domains/franchise/franchise-asset -am test`，以 `-Dtest=ExternalHandoverPgIntegrationTest#pairedNativeInstallmentsUseTheSamePrepaidReceiptDeclarationAndFinanceConfirmation` 选择用例、`-Dsurefire.failIfNoSpecifiedTests=false` 允许无匹配用例的依赖模块。指定隔离 PostgreSQL 的 `platform.test.jdbc.url`、`platform.test.jdbc.username`，以及指向配套小程序 `scripts/verify-paired-external-handover.cjs` 的 `franchise.external-handover.client`。核对 Surefire 的失败、错误和跳过均为 0，输出中的 `CUSTOMER_JOURNEY` 可串起同次运行的全部原单。

#### 在空目标中恢复一份独立备份

恢复组从固定后端 `f356e76e2b7498474526706b765090abfad195e8` 构建服务 JAR，在隔离的 PostgreSQL 17.11、Redis 7.4.11 和本地附件目录上演练。源库使用实际 Flyway 入口建库，冻结点为 2026-11-26 21:10:32（北京时间）。该次迁移历史有 **160 条**，包含 159 份版本化 SQL 和 1 个 Java 数据迁移 `V20261122.989`。新增的一条计数来自对既有 Java 迁移的核实，31 张待审表仍按原清单取证。[Java 迁移源码](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/f356e76e2b7498474526706b765090abfad195e8/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/platform/platform-org/src/main/java/db/migration/pf/V20261122_989__preserve_changed_staff_passwords.java)

| 实测顺序 | 恢复后的核对结果 |
|---|---|
| 生成与核验备份 | 保存 PostgreSQL 自定义格式备份、Redis RDB 和三份 PDF，独立留存清单摘要。备份完成后，在隔离源环境另改一条备注、一个 Redis 键及一个附件，供对照恢复时点。 |
| 恢复到新的空目标 | 使用仓库 `ops.py` 校验备份并将 PostgreSQL 单事务还原，再从 RDB 启动独立 Redis，恢复附件副本。160 条 Flyway 历史一致，复跑同一 JAR 新增 0 条。234 张表、61 条序列的名称一致，备注、Redis 键和附件均回到冻结点。 |
| 抽查业务记录 | 恢复样本客户 `81001`、已签合同 `81020`、付款计划 V1 和工程 `81030` 仍互相关联。本合同首期 400 元按 2026-12-05 到期，开工前必付 0 元。两笔实收 100 和 150 元均对上原凭据，合同余 750 元，首期余 150 元、二期余 600 元。公司积分 80 分与流水相符。3 份 PDF 的实际字节数与 SHA-256 均匹配。 |
| 复验错误情形 | 错误的受信清单摘要、被改过的 PG 备份、已有对象的目标数据库、字节被改动的附件分别被识别。本次核对结果和备份摘要已留存。 |

该次 JAR 的 SHA-256 为 `6aceedbeac054eab1d76206a97a3f95d18e8bacbf51f0f1987c2bab09b9c8edd`。前后业务核对文件具有相同摘要 `6a5f0d3b9dead9334ca7ad135bcb3cf9c9449e9e6ebe7108910d31795a7a8974`。本机从开始恢复到核对完毕用时 19.05 秒，现场的可接受恢复点和最长停机时间仍由项目负责人确定。开发机复跑脚本为 `E:/_zs_build/v3-b05-isolated-20261126/rehearse.py`，本次原始结果在同目录 `run-f3e6c5f032/evidence.json`。

现场演练待排期。准备同一冻结时点的可信备份、镜像摘要、COS 对象版本及校验清单、独立的恢复资源和业务核对人。同机全栈方案还须恢复设计服务的 PostgreSQL、Redis 和运行日志（Runtime journal），验证云端原件和授权后再核实能否开放写入。生产部署和三家门店试点由项目负责人另行安排。[仓库备份与恢复操作手册](https://github.com/phlong026/zhongshu-ai-platform-v3/blob/f356e76e2b7498474526706b765090abfad195e8/%E5%B9%B3%E5%8F%B0%E7%A8%8B%E5%BA%8F/deploy/platform/CAPABILITY_DEPLOYMENT.md#L293)

### 从内容创作到工程移交的日常入口核查

**核查结论（2026-11-26）**　同一客户能够关联发布、委托、施工合同、工程和收款原单。业务员从工作台点客户姓名进入档案时，主要操作是「记录跟进」；点列表行内的「客户跟进」才进入八个步骤。两处保存不同的业务事实，页面没有说清本次操作是否推进了哪一步。签署施工合同后也缺少能接着办理移交的入口。以下整改建议待开发，日常入口连续操作待验收。

| 实际办理环节 | 已核实的衔接和断点 | 建议改动 |
|---|---|---|
| 创作 → 外部手动发布 | 视频结果可保存为「我的作品」，再按作品编号登记门店账号和外部链接。「保存到我的作品」保存的是平台作品记录；在结果页未找到把新成片导出到手机的操作。如果素材只在小程序内，业务员难以交给外部平台发布。 | 在本人有权读取的成片旁提供「保存视频到手机」，随后提示在视频号、抖音等外部平台手动发布。保留按作品回填链接的入口。 |
| 发布 → 咨询 → 客户 | 回填时可同步登记咨询，也可从发布详情稍后补登。咨询保留发布编号，新客建档，旧客按身份关联或交负责人处理。创作首页常用工具和销售首页常用功能都没有直达「发布记录」的入口；同页登记遇到已关联旧客时，跳客户档案前少了一次另一入口已有的读权核验。 | 在作品与创作首页开放发布记录，已登记的作品可回原发布补记多笔咨询。两种登记入口共用处理结果，分别提示可打开的客户、已关联但无查看权限、待负责人处理。 |
| 客户档案 → 客户跟进 | 客户列表已显示当前步骤及完成数，档案另有「查看进度」。档案底部写沟通/实际接待，存回访日期；跟进页另办 01–06。客户来源请求失败时也会显示为空。列表的「预约回访」按档案回访日期筛选，卡片却优先用邀约步骤的联系日期，两日期不一致时可在预约名单里显示逾期。 | 列表、工作台、档案采用同一份八步摘要。档案主按钮展示「办理当前步骤」及步骤名，另留「记沟通」。日常办理可引用已登记原记录，补齐所需事实后一次提交、两处可查。回访筛选和回访提示从同一份回访记录取日期，另列已确认的约定到店时间。来源加载失败可重试。 |
| 01–06 → 委托、报价 | 邀约需客户确认，到店需实际讲解，选型需客户确认。带看可登记实际情况或说明原因跳过。外部委托能引用已核对的实测版本。第 06 步能引用同客户正式报价或上传外部报价，沟通确认后完成。 | 从对应业务原单带客户、原单编号及版本返回该步。保留普通沟通、附加资料和独立快速预算；完成规则继续要求该步所需事实，历史记录只在有据时由经办人引用。 |
| 签约 → 移交 → 工程接收 | 第 07 步读取已签施工合同。签署结果能看付款计划，业务员还需从客户返回外部委托，在「成果」页进入移交。其他移交资料齐备、付款依据仍为草案时，可提交工程待审并退回补齐；正式接收建档要求包内有已签施工合同及有效期次。工程待办可直达指定委托，接收后可直达同一项目。 | 签署结果及客户档案提供「办理工程移交」，携带本客户合同、委托及成果版本。展示指定负责人、客户确认凭据、图纸、审核依据、交底和签约锁定的付款计划；按实际状态显示待补资料、待接收或退回原因。让退回待办直达原包的补齐位置。 |
| 工程 → 开工、收款 → 客户回看 | 工程接收与实际开工分别留原单。第 08 步读取工程的实际开工日期、节点和合格凭据。工程按项目及合同期次登记实收，财务按申报核对。 | 客户侧显示移交人和接收人、当前版本、开工原单、分期实收与余额，按岗位权限回看同一组单据。 |

**为什么签过合同仍可能显示「邀约确认」。** 当前步骤取八项中第一项未完成的步骤。后段已有有效原单、前面缺记录时，签约或开工可以已有完成标记，首页仍提示补前面的步骤。建议在客户档案同时显示「最近发生的业务」和「下一项待办／待补记录」，由经办人核实早期缺项。

**日常入口已复现。** 独立隔离 PostgreSQL 17.11 配真实 Spring MVC，用同一脱敏客户 `2125948289894735874` 保存一条普通沟通和一条实际接待。客户进入「已到店」及「预约回访」筛选，档案有两条沟通，门店与总部使用的八步查询均为 0/8，八步事件为零。补齐第 02 步的接待人、客户需求及讲解反馈并提交后，两端查询均为 1/8，原沟通仍为两条。总部对照取只读查询结果。另用真实小程序列表映射验证，档案回访 12 月 1 日、邀约联系日期 11 月 25 日，在 11 月 26 日会显示「逾期 1 天」。[客户列表与回访提示](../../miniprogram/utils/platform-crm.js) · [档案办理](../../miniprogram/pages/workflow/index.js) · [八步原单入口](../../miniprogram/pages/customer-flow/index.js)

**页面和服务分别核验。** 微信开发者工具配模拟服务，从发布详情连续点「登记咨询客资」，只提交一笔咨询，来源为发布 80。关联旧客并核对读权后，档案能回看发布 80，点「查看进度」打开同一客户的八步。同一客户编号的后半段也在开发者工具配模拟服务实点。业务员签署施工合同 8012 后重开客户页，第 07 步已完成、共 7/8。由第 08 步回到委托 9012，从「外部成果」提交移交包 V1，携合同 8012、付款计划 V2 及四项资料。指定工程负责人从该委托的测试链接进入，接收后点开工程 7012，读回合同 8012 和同版期次。签署、提交、接收各写一次。单独点验八条原生导航处理函数，包含工程待办到原委托。两段开发者工具样例独立启动；服务端收款案例另在真实隔离数据库执行。

既有同客 8/8 用例通过八步服务显式保存、完成前六步，并对接真实合同、工程及财务服务。整条路径从业务首页进入、由各岗位连续操作仍待复验。日常跟进诊断运行在后端 `5f7dd9975b2862fe24cb98a22cd76137c9a5347b`，已核实该提交到 `0d01c14ec2d1483ca5c1d1294c89be8c5fe38ade` 仅调整一份测试夹具。[同一客户配对用例](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/281)

**并行开发与验收。** 客户组优先统一工作台、客户列表和档案的下一步，打通日常办理、回访日期和八步原记录。内容组同时补成片导出、历史发布和迟到咨询。工程组同时补签后直达、退回补交和客户侧状态回看。三组沿用作品 → 发布 → 客资 → 客户以及客户 → 委托/成果版本 → 施工合同及锁定期次 → 移交包 → 工程的既有编号关系，记录所属门店和办理岗。一件作品可有多笔咨询，同一旧客可保留多条来源。

联测从业务首页开始，用一位脱敏客户分岗位连续操作。覆盖隔天补咨询、重复咨询关联旧档、普通沟通后补齐到店讲解、报价换版重新确认、仅签设计合同、工程退回重交、接收后尚未开工、实际开工、部分收款及财务核实。每次返回客户页核同一原单、版本和金额，核对重复提交、无权查看和切换客户。外部平台真实发片及真机复验待渠道账号、设备和公网环境就绪。生产部署与三家门店试点由项目负责人安排。

### 后续现场验收

首项现场验收采用试点公司的一份真实脱敏案例，从实际外部发布链接与咨询建档，走完选型、实测、报价、施工签约、工程接收和实际开工。业务、工程和财务分别核原单，再用同一客户编号、委托版本和合同编号对账。节点 03 的材料到货、补发、收款与交付回执可并行抽验。

部署由用户安排。先核对在用小程序是否已包含 #266 的第 03 步兼容处理，必要时先让该兼容版本可用，再启用 #267 的后端保护。第 04 步跳过及本轮其他新增接口先在目标后端就绪，并核验公网 HTTPS 和文件传输，随后发布对应小程序。发布签约锁版时先部署 #277 后端，使各版本小程序调用签后写入口均受约束，再发布 #278 的只读及重试处理。正式部署与试点日期待用户安排。

隔离恢复已通过。现场可并行提取同一时点的源、目标差异，并由各岗位准备 B05 所需证据。B01 的免带看与施工签约、B03 的施工签约时锁定付款计划均已确认。业务方后续提供正式薪酬公式和公共费用分摊口径。三家门店名单和时间由用户另行提供，正式部署由用户负责。图纸及报价接口待设计软件方提供样本、接口说明和测试条件，现行手动上传继续可用。

### 已确认事项与待补资料

| 事项 | 需要提供或确认的内容 | 用于关闭 |
|---|---|---|
| B01 八步业务流程 | 两项口径均已确认。主动跳过带看须填写原因，计入进度。仅签设计合同保留施工签约待办，正式施工合同已签署才完成第 07 步。 | CRM-01、CRM-02、CRM-03 |
| B02 绩效薪酬 | 项目负责人后续提供正式薪酬公式、适用月份、计提及退款处理规则，该规则实现暂缓。 | PERF-01、PERF-02、PERF-04 |
| B03 付款计划 | 已确认。正式施工合同签署时锁定付款计划版本。签署后任何岗位均不得修改期数、每期金额、付款日期或触发工程节点。工程按签约版本收款，财务核对实收。两种签后写入口、签约并发、旧版页面重试和收款核对均已验证。 | ENG-01、FIN-01 |
| B04 成本分摊 | 公共费用分摊规则暂缓实现，待用户提供口径和能与项目直接成本区分的样例。 | FIN-04、FIN-06、FIN-07 |
| B05 迁移与恢复 | 具体名单待源、目标同一时点的脱敏盘点。请按「B05 的积分价目例外与已确认的品牌、门店处理规则」核对积分价目冲突。品牌、未绑定门店及其服务区按已确认的证据范围处理，仅在原始记录不能解决歧义时提交个案。27 张表由相应岗位提供凭据。目标恢复时点、允许停机时长及现场备份仍待安排。 | QA-01、QA-02、QA-03、QA-07 |
| B06 三家公司与真机 | 用户负责部署，三家门店的名单、时间、岗位、账号与真机由用户另排。上线前核验公网 HTTPS 后端及文件传输。试点核对存量订单到账、退款与共享积分。真实新充值验收随将来重新启用安排。 | QA-05、QA-06、WALLET-02、WALLET-04、WALLET-05 |
| 外部设计图纸与报价接口 | 现阶段由用户导出并手动上传同一客户委托下的成果、施工图和专业审核依据。自动接口需软件方提供样本、接口说明和测试条件。 | DESIGN-04、ENTRUST-02、CRM-01 |
| 成交归因 | 业务负责人确认成交事件，以及同一客户有多个内容触点时的分配规则。现有签约和净实收指标可分别核对。 | CONTENT-05、CONTENT-07 |
| 尚未配置个人周期目标的门店 | 现有员工月目标由独立接口维护，旧目标页只读。若门店需在小程序从零开月，业务负责人需确认维护岗位和目标编辑入口。在此之前可办理已配置的周期。 | PERF-05 |
| 公司积分的补充方式 | 已确认为超级管理员向门店所属公司的共享池加分，新充值默认关闭。#245/#246 已合并。关闭与重新开启、存量订单与退款均已隔离验证。真实渠道由用户安排。 | WALLET-01、WALLET-02、WALLET-03 |

### 客户八步完成标准与外部设计衔接（侧聊交接时的记录）

以下保存交接时依据文档提交 `13bdaab3877b341e6e79f1a1b2bc5339713830fa` 核定的口径与当时验证记录。2026 年 11 月 26 日晚，项目负责人确认免带看可主动跳过并计进度，仅签设计合同保持施工签约待办，付款计划在正式施工合同签署时锁定、随同版合同移交，线上新充值默认关闭。实际接续状态以本节前方任务和配对表为准。目标是同一客户只录一次业务事实。面向加盟商内部的客户跟进页引用正式业务原单，显示进度、缺项和办理入口。2026 年 11 月 26 日核对了 11 月 24 日《众墅之家 AI 赋能平台上线前成果汇报》的腾讯会议回顾纪要、《合家美宅业务培训 2026·客户跟进》及现有项目流程。下表中尚未实现的操作为后续开发标准。

#### 纪要核对与实施口径

- **保留快速预算。** 腾讯会议纪要第二节原记为取消快速预算。项目负责人于 11 月 26 日确认该处记录有误。实施以本次确认为准。快速预算继续用于前期测算，延续现有入口、计价、保存、回看和导出能力。
- 纪要第二节确认按八步记录客户业务事实、自动计算进度，保留标准户型灵感库供内部参考，正式设计成果由用户手动上传。第三节确认自定义工程付款节点与比例随委托流转，设计费、工程款等业务资金按实际收付登记，每个项目独立记账。
- 纪要待办要求设计软件方提供图纸与报价的对外接口。当前交付方式为用户从外部软件导出后上传。自动对接待提供图纸与报价样本、接口说明和测试条件。

#### 八步业务完成标准

| 步骤 | 何时完成 | 小程序最少操作 | 开发进展 |
|---|---|---|---|
| 01 邀约进店 | 客户明确答应到店，并约定日期和时段。未约成可记录下次联系。 | 选渠道和预约时间或时段，确认「已约到店」。发生日期默认当天，可更正。 | 主线已校验客户确认、渠道和具体到店日期时间。时段快捷选项及默认发生日期待开发。 |
| 02 店面讲解 | 客户实际到店，已按客户需求讲解，并记录一个沟通结果。 | 带出接待人和客户档案已有建房需求，选沟通结果，必要时补一句。 | 主线已有接待人、需求及反馈。自动带出和快捷结果待开发。 |
| 03 户型选择 | 客户确认一套暂定标准户型，或确认了定制方向。 | 从灵感库选一套后确认，或选「外部定制」并留一句已确定的方向。 | 主线支持引用灵感库或上传外部资料。只确认定制方向即可继续的分支待开发。 |
| 04 带看工地 | 已实地带看，记录工地和客户反馈。 | 选常用工地、点一个反馈，照片选填。 | 主线已有工地名称和反馈，地点、反馈选项待完善。未发生的带看保持原状，免做及进度规则待业务确认。 |
| 05 现场测量 | 有已核对的实测单，含地址、面宽、进深、面积、朝向及影响设计的现场高差，可补现场照片。 | 引用同一客户的测量单，或拍照上传纸质单一次。 | 主线仍需手填尺寸、人员，与附件一起提交。后续建立测量单与外部委托的受控引用，避免重复录入。 |
| 06 别墅报价 | 已向客户发出或讲解某版正式建造报价，并记录沟通结果。客户要求调整时保留本版沟通事实，新版单独确认。 | 选择本客户的正式报价原单并记录结果。外部正式报价上传一次，登记版本和结果。 | 原单选择、查看、金额与版本快照和历史已通过本地双端验证，待配套合并和门店真机验收。 |
| 07 合同签约 | 正式施工合同已签署，有有效签约日期、金额和已签附件。 | 在合同模块办理一次，客户步骤自动回写。可从正式报价进入现有付款计划页面。 | 主线已有自动判定。仅签设计服务合同是否算第 07 步完成待业务确认，默认作为设计服务单独记录。 |
| 08 开工大吉 | 项目经理登记实际开工日期和有效凭据，且工程模块完成开工准备及约定资金校验。 | 在工程原单登记实际开工，客户步骤自动回写。 | 主线已自动判定。四通一平、业主和项目经理确认需统一在工程开工单上核对，表单优化待开发。 |

前六步区分草稿与已完成，后两步从合同和工程取正式事实。允许按实际顺序补录，已完成步骤更正时记录原因并保留历史。当前按完成步数计算，每步为 12.5%。未发生的步骤保持真实状态，待业务定下免做口径后再调整进度规则。

#### 正式设计和报价、合同、工程的衔接

1. 客户页完成选型方向和现场测量后，业务人员在「外部设计委托」登记服务方、需求、范围及可选的外部项目编号，并随委托记录相关资料和付款约定版本。灵感库供选型参考，快速预算继续用于门店、客户和已有方案的前期测算。
2. 正式设计在外部 AI 设计软件中完成。用户将导出文件手动上传到对应客户的委托，按「外部设计成果」「施工图文件」「专业审核依据」登记，填写图纸版本和实际交付日期。现有小程序上传支持 PDF、JPG、PNG，单份限 30 MB。施工使用以专业审核依据和客户确认凭据为准。设计文件更正生成新成果版本，历史原件继续可查。
3. 第 06 步可引用交易模块中本客户已有的正式建造报价原单，也可登记外部软件导出的正式建造报价。引用原单时由后端读取编号、金额和版本。登记外部报价时上传原件，记录金额、范围、版本和沟通结果。更换报价后重新确认沟通。外部设计费用独立记账，快速预算继续用于前期测算。
4. 签约沿用现有合同及自定义分期付款流程。业务在委托中带入本版付款节点与比例，正式签约后按合同约定，工程接收本版移交资料及付款约定。合同已签凭证驱动第 07 步。工程登记实际开工时校验应满足的约定，以实际收款登记为依据。由正式报价建合同时带入原报价金额，合同差异在合同端确认。
5. 业务向指定工程负责人提交同一客户同一委托的外部成果版本、客户确认凭据、交底说明和随包付款约定。工程负责人可按原版本接收、退回，业务补齐后重交。工程接收与实际开工分别记录，只有实际开工驱动第 08 步。

#### 交接时的实现与验收

- 第 06 步从既有正式报价列表选择原单。后端在保存和完成时核对客户归属及有效金额，只采用正式报价原单的金额和版本。页面记录经办人本次沟通结果，支持从已关联步骤直接查看原单。使用同一编号写历史，保留原上传报价的兼容办理和快速预算入口。本轮使用现有跟进字段快照和交易原单，数据库表结构沿用现状。
- 验收用同一测试客户建立两版报价，选择第一版并记录实际沟通，核对第 06 步完成、原单、金额、版本及历史一致。覆盖选到另一客户报价、未沟通直接完成、重复请求和补传外部报价。小程序核对事实回读、原单跳转及原快速预算入口。本轮交付先配对后端与小程序，再用已部署的公网 HTTPS 环境进行真机复核。
- 本轮本地代码验证通过。后端 `d667be636e23cfad57c314e1b97e7c8a6dc5b33d` 在隔离 PostgreSQL 执行客户跟进、正式报价及三期合同 35 项，全部通过。小程序代码 `594f54bfd3cc42a146c0248cb2eebb79b372d327` 合入当时的主线版本后，在测试提交 `8332503459830c844486fc1d53f9a9747dd23bcf` 完整 `npm run check` 通过，其中客户流程 41 项、WXML 130 个。按 Git 签入换行统计，主包剩余 131,655 字节。双端主线合入和微信真机验收待完成。
- 后续先用真实脱敏的外部图纸及报价样本，验证上传、查看原版本、客户确认、变更重交、工程接收及继承自定义付款约定。外部设计软件接口另行验收。测量单复用及前四步快捷操作随后推进。
- 纪要要求安排测试账号对接人员和三家陪跑公司。正式验收用同一个客户贯穿选型、测量、报价、合同、移交、开工和项目账本，检查跨门店访问隔离、历史版本、退回重交和重复提交。两份文档随实际合并及验收结果同步更新。

各功能按上述提交验证。目标环境发布、生产迁移及真实微信付款单独登记，当前均待验收。

## 1. 执行规则

1. 按 V1.1 的 13 个执行节点顺序推进；无依赖的子任务可并行，但不得跳过前置契约。
2. 每个子任务先审计当前代码、数据库、接口、权限及调用链，再评估开源复用。
3. 按 TDD 先建立失败测试，再实现代码。
4. 子任务独立开发、专项评审、独立 Commit；同一模块形成模块 PR。
5. 小程序改动进入 `main`；Java 后端/Vue 管理台改动进入 `backend-main`。跨端任务建立配对 PR。
6. PR CI 全绿、专项评审通过后合并；合并后继续检查主分支 CI、前后端精确 SHA 配对、数据库迁移和业务场景。
7. 只有代码、评审、CI、配对、数据库及业务验收全部通过，才标记“已完成”。
8. 不执行未经授权的生产账务操作、真实支付消费、生产数据清理或生产部署。

## 2. BASE 基线审计记录

### 2.1 仓库基线（2026-11-25）

| 项目 | 当前值 | 证据 |
|---|---|---|
| 小程序主线 | `main` / `6543e180013cfd803eabb535e2f9883e700f7f55` | GitHub branches/main |
| 后端主线 | `backend-main` / `27afed9127f06d2625d8b47af568edf3bccc40d5` | GitHub branches/backend-main |
| 开放 PR | 0 | GitHub pulls?state=open |
| #171 | 已合并至 main | merge SHA `2447bde9bf3c0a1832bd37d5df5538f93d12d227` |
| #172 | 已合并至 main | merge SHA `6543e180013cfd803eabb535e2f9883e700f7f55` |
| #173 | 已合并至 backend-main | merge SHA `27afed9127f06d2625d8b47af568edf3bccc40d5` |
| #166 | 已合并至 backend-main | merge SHA `762efc68de328859ab593a88a27f78b968f0b5b9` |
| #168 | 已合并至 main | merge SHA `5aee021fab34e7f653d63d448909661e0aa470d4` |
| #84 | 已合并至 main | 按 V1.1 转为业务回归，不重复开发 |

以上 SHA 是本次执行基线快照；每个新 PR、配对验收及发布候选均须重新读取完整 SHA。开放 PR 数量 0 是创建本执行日志 PR #175 之前的快照；#175 为本日志自身的文档 PR，不属于 V1.1 业务整改开放项。

### 2.2 CI 与配对状态

| 分支 | 工作流 | SHA | 本次核查结果 |
|---|---|---|---|
| main | Frontend CI | `6543e180013cfd803eabb535e2f9883e700f7f55` | success |
| main | Paired frontend/backend verification | `6543e180013cfd803eabb535e2f9883e700f7f55` | success（run 45631391774） |
| backend-main | Backend CI | `27afed9127f06d2625d8b47af568edf3bccc40d5` | success |
| backend-main | Paired release rehearsal | `27afed9127f06d2625d8b47af568edf3bccc40d5` | success |

- Frontend CI 使用 Node.js 24，执行 `npm ci`、`npm run check`、`npm run check:role-homes`、`npm run check:packages`。
- Backend CI 使用 JDK 17、PostgreSQL 17；发布回归还包含 Redis 7。
- 后端构建入口为 `平台程序/script/platform/build-platform.sh`，不得用跳过测试的构建替代正式门禁。
- 两条主分支当前 GitHub API 返回 `protected=false`。在分支保护未配置前，执行中严格人工执行 PR/CI 门禁；不得因 API 允许直接推送而绕过流程。

### 2.3 代码与数据库资产盘点

| 资产 | 盘点结果 | 说明 |
|---|---:|---|
| 后端平台目录文件条目 | 7,592 | Git tree 静态盘点 |
| 平台基础能力模块 | 7 | platform-command/org/workflow/authz/identity/wallet/test-support |
| 加盟业务模块 | 12 | franchise-api/test-support/org/crm/commission/finance/design/sales/asset/content/engineering/performance |
| 正向 SQL 迁移文件 | 232 | 全后端模块静态计数 |
| 回滚 SQL 文件 | 146 | 目录内 migration-undo 静态计数 |
| 后端测试文件 | 501 | Java/JS/Python 等测试文件静态计数 |

后端 README 明确要求保留已发布 Flyway 迁移；修复应追加新迁移，不得改写已经应用的迁移。真实数据库当前版本、迁移执行状态和备份恢复能力仍需在 QA 节点通过隔离环境核验。

### 2.4 开源复用评估

- 已核对官方 [YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro) 及 [Yudao 官方文档](https://doc.iocoder.cn/intro/)。
- 当前后端 POM 已基于 Yudao，且已有独立平台能力层和加盟业务域层；现有底座覆盖系统权限、租户、工作流、支付及基础设施能力。
- 执行策略：优先复用当前仓库的 Yudao 模块、platform 模块和 franchise 领域模块；仅当某个子任务存在明确能力缺口时，再评估外部开源组件。禁止为整改而引入另一套 CRM/ERP/支付/工作流平台。
- 各子任务仍需进行针对性开源检索并记录“复用/不复用”理由。

### 2.5 已确认的代码差异线索

**AUTH-06｜公开门店地址**

- 当前已有 `FranchiseDirectoryService` 和总部管理台加盟商名录维护入口。
- 当前已有独立的 `public_name` 字段及“公开地图名称”维护接口；迁移 `V20261125.1045__franchise_public_map_name.sql` 只增加 `public_name`。
- `FranchiseMapService.snapshot()` 当前将 `publicAddress` 固定为空字符串；当前已读迁移中未发现独立 `public_address` 字段。
- 后续应独立维护公开地址、建立服务端授权投影和测试；禁止从 `pf_org_profile.address` 或内部资料自动回填。

**CONTENT-06｜我的作品**

- 当前已发现 `ContentTaskResultService` 用于将已成功任务的结果持久化到文案/脚本记录；`ContentTaskController` 提供个人任务列表、详情和隐藏任务入口。
- 任务隐藏不等于删除作品；任务结果持久化也不等于作品库已形成完整的权威列表/删除/恢复接口。
- 具体作品数据模型、前端“我的作品”入口及后端 DTO/列表/删除/恢复能力，必须在 CONTENT-06 开始前继续追踪调用链，不得猜测接口字段。

**FIN-03A｜财务更正恢复**

- #171 已合并；代码补充了财务负责人视图的“作废后继续重登”入口，沿用已有更正链，不重发作废请求。
- `scripts/verify-finance-correction-reconcile.cjs` 覆盖回执丢失、普通作废过滤、替代单过滤、权限、换岗迟到响应、退款/支出退回分流等场景。
- 复核发现该测试此前未进入 `npm run check` 的正式链路；现将其接入 `check:finance-workbench`。
- 后端 `FinRefundThreeDirectionIntegrationTest` 已覆盖更正链 CAS、并发单赢家及支出退回更正，后端财务模块 CI 负责执行 PostgreSQL 集成测试。
- 未操作真实账务；微信真机和财务岗位业务验收仍需完成。不得把 #171 合并或专项脚本单独通过视为 FIN-03A 已完成。

## 3. 13 个执行节点的阶段记录

| 节点 | 执行主题 | 关联任务 | 当前状态 | 进入条件/完成标准 |
|---|---|---|---|---|
| 00 | 基线核查与任务冻结 | BASE-01～06 | 已完成 | 基线、代码差异、开源复用及执行日志 PR #175 已合并 |
| 01 | 财务更正恢复专项验收 | FIN-03A / #171 / #195 | 待业务验收 | 专项回归已接入 CI；最新主干配对已通过；待财务岗位真实业务验收 |
| 02 | 门店公开地址与总部权限 | AUTH-06 / #196 | 已合并，待业务验收 | 独立地址字段、授权维护、地图投影和隔离测试已合并；待迁移部署及总部账号验收 |
| 03 | 工程付款、到货与交付验收 | ENG-02/03/04、ENG-IDEM-01～05 | 进行中 | #199、#202、#204、#206、#207、#208 已合并。#209、#210 接续并发与授权核验 |
| 04 | 发布链接与客资关联验收 | CONTENT-01～04 / #164/#168/#155/#84 | 待业务验收 | 小程序 75 项、后端 18 项发布及 44 项客资回归通过。待真机及岗位验收 |
| 05 | 历史 PR 差异核查 | #26/#27/#106/#107 | 代码差异已核对 | 主线覆盖、旧模拟流程及正式渠道补验记录见第 10 节 |
| 06 | 我的作品功能补齐 | CONTENT-06A/B/C | 双端 PR 评审 | #216 / #218。作者权限、回收及恢复、历史文本、已发布回放及原始客户来源 |
| 07 | 内容获客成交统计 | CONTENT-07 | 双端 PR 评审 | #213 / #214。有效签约与净实收客户分别去重，正式成交口径待业务确认 |
| 08 | 设计生成退出与快速预算保留 | DESIGN-02/03/05 | 进行中 | 2026-11-26 确认保留快速预算的入口、深链、生成、保存与历史。旧平面与立面生成入口及写接口退出 |
| 09 | 工程成本与财务账本 | ENG-04～06 / FIN-04～08 | 三项 PR 评审，流转入口开发中 | 数量保护 #215，项目应付余额与不可变历史 #219 / #217 |
| 10 | 客户、委托、合同、工程全链路 | CRM / ENTRUST / ENG | 流程配对验收中 | 八步、外部成果、合同、工程接收和历史委托。跟进待办日期和入口已定位 |
| 11 | 绩效、公告、导航及公司额度 | PERF / OPS / WALLET | 自动化验收与培训修复中 | 岗位导航、公司额度与真实页面。渠道及正式薪酬规则待补 |
| 12 | 数据迁移与正式上线验收 | QA-01～08 | 隔离环境演练中 | 最终配对、迁移/恢复、真机、三家公司陪跑分别记录 |

## 4. 逐任务证据登记格式

后续每项子任务按以下格式追加，不覆盖历史记录：

| 字段 | 记录内容 |
|---|---|
| 任务编号 | 如 AUTH-06 |
| 审计基线 | 前后端完整 SHA |
| 开源复用评估 | 仓库/组件、复用结论及原因 |
| 根因与影响 | 可复现证据、业务影响 |
| 改动范围 | 页面、接口、权限、数据表、迁移 |
| 测试 | 先失败后通过；单元/集成/权限/异常/并发 |
| 专项评审 | 结论、阻断项、整改记录 |
| 分支与 Commit | 分支名、独立 Commit SHA |
| PR 与 CI | PR 编号、检查结果、合并 SHA |
| 配对验收 | 前后端精确 SHA、运行记录 |
| 数据库 | 迁移文件、升级兼容、回滚/恢复 |
| 业务验收 | 岗位、场景、预期、实际结果、证据 |
| 最终状态 | 待开发/进行中/待核查/待验收/已完成 |

## 5. 节点 03 启动时的未完成事项

1. 节点 01：FIN-03A-CI-01 已合并，Frontend CI、Backend CI 和配对验收均通过；仍待财务岗位真实业务验收。
2. 节点 02：AUTH-06 的 PR #196 已合并至 backend-main；Flyway 迁移 V20261126.1001 已在 CI 隔离环境演练，仍待生产部署、迁移执行及总部真实账号验收。
3. 节点 03 的开发、PR、配对基线和岗位验收缺口见第 8 节。
4. CONTENT-06 尚需完成作品模型、列表、删除/恢复的完整代码追踪。
5. ENG-02/03/04 等已合并代码仍需真实业务场景回归；当前未执行生产部署或真机验收。
6. 当前分支保护未启用，所有合并必须人工核验 CI 与评审，不得绕过。

---
本文件只记录执行管理状态；不代表已部署、已操作生产数据或已通过真机/三家公司验收。


## 6. 节点 00 完成与节点 01 执行记录（2026-11-26 更新）

### 6.1 节点 00 完成

- 执行日志 PR #175 已合并至 `main`，合并 SHA：`08ae5942876abe8ce5c7b794315e9cb5452fae99`。
- 基线版本 `main=6543e180013cfd803eabb535e2f9883e700f7f55` + `backend-main=27afed9127f06d2625d8b47af568edf3bccc40d5` 的配对验收 run 45631391774 成功。
- PR #175 合并后 `main=08ae594...` 的 Frontend CI run 45637055828 成功，配对验收 run 45637054667 成功。
- 后续主线持续合并其他独立 PR。当前 `main=9ce28772586fe3a6060b98265498293832f18b35` 的 Frontend CI run 45732708268 成功、配对验收 run 45732708234 成功。当前 `backend-main=196944dd5e2e49644e244e794e08683d95eaa154` 的 Backend CI run 45738883617 和 Paired release rehearsal run 45738883576 在本次记录时仍在执行；节点 01 必须以新 PR 的精确配对结果复验，不沿用旧 SHA 的结果。

### 6.2 FIN-03A 代码与权限专项复核

- 前端 `pendingRefundCorrections` 仅允许 `finance-lead` 看到“退款待重登”，且只筛选 `status=已作废`、`voidReason=更正重登`、`replacedById=null` 的记录。
- 续办时重新读取 `/franchise/finance/refunds`；页面再次确认原单仍待重登后才恢复表单。
- `correctionGuard` 检查 session、当前岗位、页面代次及页面生命周期；续办不再次调用作废接口。
- 后端沿用现有 `replacesId` 更正链及 CAS 替代权；客户退款与支出退回分别使用原收款/付款来源。
- 权限矩阵：finance 可查询/登记；finance-lead 额外可作废付款/退款；boss/owner 可查询并作废退款，不授予作废付款权限。不得将 UI 显示作为后端授权，最终以控制器 `@PreAuthorize` 和组织归属校验为准。
- 本任务不修改后端账务逻辑，不新增数据库迁移，不执行真实账务。

### 6.3 FIN-03A-CI-01｜专项回归接入正式 CI

| 字段 | 内容 |
|---|---|
| 问题 | 专项回归脚本存在，但此前未进入标准 `npm run check` 路径 |
| 修改 | 将 `node scripts/verify-finance-correction-reconcile.cjs` 加入 `check:finance-workbench` |
| 影响 | `npm run check` 与 `check:role-homes` 会间接执行 |
| 首轮分支 | `fix/finance-correction-ci-20261125` |
| 首轮 PR | #176 |
| 首轮测试 | Frontend CI run 45637730231 成功；日志明确输出“更正链、跨页恢复与对账接线全部 PASS”；配对 run 45637730230 成功 |
| 基线变化 | PR #176 创建后 main 前进 25 个提交，backend-main 也继续前进；旧 PR 虽无文件冲突，但其检查结果不作为最新主干的最终验收 |
| 当前分支 | `fix/finance-correction-ci-20261126`，从最新 main `9ce28772...` 创建，重新应用同一项测试接入及执行日志更新 |
| 数据库 | 无迁移 |
| 后端 | 无代码修改；复用现有 PostgreSQL 更正链集成测试 |
| 当前状态 | 最新基线 PR 待创建及 CI/配对复验 |

---

本文件只记录执行管理状态；不代表已部署、已操作生产数据或已通过真机/三家公司验收。


---

## 7. 节点 01/02 合并记录与节点 03 开发前审计（2026-11-26）

### 7.1 最新主线基线

| 主线 | 当前 SHA | 说明 |
|---|---|---|
| 小程序 `main` | `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` | 已包含 PR #195、#194 |
| 后端 `backend-main` | `11014a5cf936166b71134bf40f838ad93b04ce43` | 已包含 PR #196 |

PR #194（客户列表分页一致性）已合并至 main，作为节点 02 配对验收使用的前端基线；该 PR 不属于 13 个整改节点的重复开发任务。

### 7.2 节点 01｜FIN-03A-CI-01

| 字段 | 结果 |
|---|---|
| PR | [#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) |
| 目标分支 | main |
| 合并时间 | 2026-11-26 10:13:27（北京时间） |
| 合并 SHA | `41136df0162ea221ff2ba12b4f61c4da91a78c3e` |
| Frontend CI | run `45739262116` success |
| Paired frontend/backend verification | run `45739262044` success |
| Backend CI 基线 | run `45738883617` success |
| Paired release rehearsal 基线 | run `45738883576` success |
| 修改范围 | 将财务更正恢复专项脚本接入标准 `npm run check` |
| 数据库 | 无迁移 |
| 最终状态 | 代码、CI 和配对验收完成；待财务岗位真实业务验收 |

PR #176 因基线落后关闭，最终以 #195 最新 main 基线结果为准。未操作真实账务。

### 7.3 节点 02｜AUTH-06 公开门店地址

| 字段 | 结果 |
|---|---|
| PR | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196) |
| 分支 | `fix/franchise-public-address-20261126` |
| 目标分支 | backend-main |
| 合并时间 | 2026-11-26 11:07:01（北京时间） |
| 合并 SHA | `11014a5cf936166b71134bf40f838ad93b04ce43` |
| PR head SHA | `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58` |
| Backend CI | run `45745242295` success |
| Paired release rehearsal | run `45745321948` success |
| 精确配对 | main `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` + backend PR head `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58` |
| 管理台测试 | 116 项通过，管理台构建通过 |
| 数据库迁移 | `V20261126.1001__franchise_public_map_address.sql` |
| 最终状态 | 代码、专项评审、CI、配对验收完成；待生产迁移部署及总部真实账号验收 |

本次实现边界：
- 新增独立 `public_address`、`public_address_authorized`、授权人和授权时间字段；默认未授权，不从 `pf_org_profile.address` 回填。
- 只有 FRANCHISE 档案可授权；撤销授权清空公开地址及当前授权元数据。
- 地图接口只返回明确授权的公开地址；加盟商仍受同品牌过滤，总部仍受当前租户约束。
- 小程序现有地图列表、详情和搜索已支持 `publicAddress`，本轮没有重复引入地图组件。
- CI 隔离环境已执行新 Flyway 迁移；这不代表生产数据库已迁移。
- 未执行生产部署、真实门店地址录入或总部账号现场验收。

### 7.4 节点 03｜工程付款、到货与交付预审

现有 #166/#167/#173 代码已覆盖主要业务门禁，现进入最新 backend-main 的专项回归。预审发现以下幂等请求指纹缺项，先以失败测试确认，再分别修复：

| 子任务 | 预审位置 | 初步发现 | 计划验收 |
|---|---|---|---|
| ENG-IDEM-01 | `EngCollectionService.register` | 收款登记指纹未覆盖实际发生日期、凭证资产及项目归属 | 同一 key 更换日期/凭证/项目必须拒绝，原收款不得重复入账 |
| ENG-IDEM-02 | `EngMaterialService.submitPlan` | 材料计划指纹只记录节点、日期和行数，未覆盖材料名称、规格、单位及数量 | 同一 key 更换材料明细必须拒绝，不得静默返回旧计划 |
| ENG-IDEM-03 | `EngMaterialService.arrival` | 到货、补发和质量处理指纹未完整覆盖预计数量、交易号、凭证、发生日期及备注 | 原到货/补发/质量处理重试幂等；任一业务字段变化必须拒绝 |
| ENG-IDEM-04 | `EngMaterialService.arrivalCorrection` / `movement` | 到货更正原因、材料领用备注及接收人未完整纳入指纹 | 相同 key 不得静默忽略更正原因、接收人或备注变更 |
| ENG-IDEM-05 | `EngClosureService.deliver` / `settle` | 交付备注、结算凭证资产未完整纳入指纹 | 同一 key 更换交付备注或结算凭证必须拒绝 |

上述是代码审计线索，需通过测试确认具体影响；开发时必须同时处理历史幂等事件兼容，不得因扩大 payload 导致合法历史重试被误判为新请求。节点 03 仍需覆盖五期付款净额、退款后重新拦截、待补到货不计入实际到货、质量处理、交付凭据和交付后尾款规则。

---

本文件状态仅记录远程代码、CI 和配对验收证据；不代表生产部署、生产数据库迁移、真实账务操作或三家公司陪跑完成。

## 8. 节点 03 开发记录（2026-11-26）

### ENG-IDEM-01 工程收款

- [#199](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/199) 于北京时间 12:26 合入 `backend-main`，合并 SHA 为 `0c8238ee51a4955ea98be1c82c35651f55edf20f`。PR head 为 `8f6ff3b035a44d0faad351853af737cf3a62a000`。[Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45752832906) 和 [PR 配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45752895245) 均通过。
- 在 PostgreSQL 17 隔离库复现省略收款日期的请求跨日重试被拒。原三字段事件和 #199 写入的展开日期事件需分别覆盖。接续 [#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202)，同题 #203 已关闭。收款仍须由工程与财务岗位验收。
- #202 补测提交 `fa1cc1ea413721ea8f4eb9a3857aa38db24b5ef3` 复现新单显式填写日期后省略日期仍被接受。修复提交 `7494c869c00848fe49971f74c01a1605f73ef147` 区分新旧事件和日期输入。隔离库 `EngGateNetReconcileIntegrationTest` 共 10 项通过，0 跳过。前端配对目标为 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`。

### ENG-IDEM-02 材料计划

- 审计基线为小程序 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` 和后端 `0c8238ee51a4955ea98be1c82c35651f55edf20f`。原指纹只有节点、日期和行数，改变一行材料仍返回成功，合法重试又遗漏原单编号。
- [#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204) 的测试提交为 `931b17ddd6e01c9291ac052a7903784400e908e7`，实现为 `6d02bdad48db19f700e157bef1b3db39e92e5fce`。复用已有工程行锁、命令事件与材料计划编号，数据库无新增迁移。
- 两项新增测试先失败。修复后在隔离 PostgreSQL 17 执行 `EngMaterialAndPlanGuardIntegrationTest`，37 项通过、0 跳过。覆盖同键改变单行名称、规格、单位、数量或备注，以及相同输入跨批次、数量调整后的重试。
- 旧事件回读仅接受可以唯一核对的原批次。存在多份候选单、缺少备注留痕或原数量已调整时，服务提示核对原计划。请工程岗位在历史数据验收中提供此类样例。

### ENG-IDEM-03 到货、补发与质检

- [#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206) 基于后端 `0c8238ee51a4955ea98be1c82c35651f55edf20f`，配对前端目标为 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`。测试提交 `12a1064ff1399f573bbac2bc049c870910ede82f`，实现提交 `753ffe4689704b5fa48b63035e6391e85cc71cb7`。
- 原实现遗漏预计数量、流水、日期、凭证或处置说明。新事件按命令类型记录实际业务输入，供货单和工程一致后才返回原结果。旧事件通过实收行、补发批次或已保存的质检处置核对。复用现有工程行锁、命令事件及批次记录，无数据库迁移。
- 两个 PostgreSQL 用例先失败。修复后 `EngMaterialAndPlanGuardIntegrationTest` 37 项全部通过。含首收和补发原单重试、单字段变化拒绝、质检说明和凭证替换拒绝、历史事件回读。
- 旧事件缺少批次编号且同一供货单多批实收无法区分，或原应到数量已经更正时，请工程岗位核对原到货单据。

### ENG-IDEM-04 到货更正与材料流转

- [#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207) 在原单重试时核对更正原因、流转备注与接收人。旧事件回读更正记录或工程、作者对应的材料流转记录。无法区分原始记录时提示核对历史单据。
- 测试提交 `b5b992785f818e3194dd66c7ccd9c9740b5b6fae`，实现提交 `1dc892adb334783f717eaa8a7e9951100878f2ea`。两项业务差异在修复前复现。修复后工程材料集成测试 36 项通过、0 跳过。

### ENG-IDEM-05 工程交付与结算

- [#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208) 的测试覆盖交付备注、结算凭证、归档后重试及跨项目核对。复现同时提交同一结算时第二个请求收到过期版本错误。修复后在读取结算单和命令事件前先锁定工程，两个请求取得同一结算结果。新结算引用凭证时检查组织、验收状态与资产类型。
- 首轮测试提交 `cefea659ecc8678f43efb3f92a2f63899f90ece3`，实现提交 `7a1597ed5e28043436c0e78c9557c6804cd4f2d7`。独立后端分支在 PostgreSQL 17 运行两组工程集成测试，42 项通过，0 跳过。小程序主线工程凭据、联动、材料账期、节点更正四组回归共 38 项通过。

### 主线与 PR 验证

- 小程序主线 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`，后端主线 `0c8238ee51a4955ea98be1c82c35651f55edf20f`。#199 合并后的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45755260544) 和[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45755260557) 均通过。
- #202 的[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756490102)通过。后端打包下载 Spring Boot Maven 插件时 Maven Central 返回 HTTP 429，已单独重跑失败作业。[Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756470325)
- #204 的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756097825) 和[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45756097846)通过。#206 的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757130825) 和[配对演练](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757130824)通过。#207 的 [Backend CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757465179) 通过。各 PR 的小程序配对目标均为上述完整 SHA。

- 将 #202、#204、#206、#207、#208 的上述提交依序合入本地临时提交 `90aab7619d7a434357f950fed3136c9a9aef7bd1`。两组 PostgreSQL 工程集成测试共 51 项通过，0 跳过。远程后端主线仍以 #199 的合并 SHA 为准。

## 9. 节点 04 发布链接与客资关联

在上述两条主线重新执行小程序 `verify-live-manual-publication`、`verify-experience-lead-route`、`verify-live-content-p1` 和 `verify-publication-pending`，75 项全部通过。覆盖提交后回执丢失、使用原请求重试、切换账号与岗位、链接 A/B 校验、把客资关联到实际保存的发布记录。

隔离后端执行发布服务及 PostgreSQL 发布测试 18 项，CRM 与客资导入 44 项，全部通过。验证本人作品和平台账号归属、已发布记录重复回填、同手机号转待关联、重复导入与跨组织隔离。实际手机弱网、真实平台发布回执及岗位交接由业务方集中补验。

## 10. 节点 05 历史 PR 独有差异

| 历史 PR 与复核版本 | 差异对照与处理 |
|---|---|
| #26 `4bb7f772fd5b474e3bb20e0780a603b153efbf43` | 旧补丁改用手工工程专用路由。现行小程序与后端已使用合同备案路由 `projects/contract-filing`，按已签约合同或明确的独立建档模式处理。保留当前接口 |
| #27 `6d72c4061610bb9c65473b6e235f517204d06694` | 原分支校验工程与合同归属、按应付锁串行处理垫付结清。现行交接冻结测试 9 项、财务工作台 15 项通过，含同一笔垫付退款、报销、作废及结清并发的净额断言 |
| #106 `976c9c9c6904002eda84db4925cce26ae5a0ab8e` | 旧统一模拟收银台已退役。可沿用的切换身份后迟到回包保护已在现行公司额度与套餐页验证；正式 `check:wallet` 两组共 25 项通过。旧 `verify-recharge-flow` 依赖已退役的钱包桥接，执行返回“钱包功能未开通” |
| #107 `154232619cbe6dcf9455103a781147ca4261ab89` | 旧全局 MOCK 模式、模拟到账及迁移 .997～.999 继续退役。现行支付事实校验、公司充值状态及虚拟支付通知 HTTP 集成共 15 项通过。真实渠道退款与通知由节点 12 单独演练 |

## 11. 并行开发与工程专项评审（2026-11-26）

| 工程 PR | 独立评审与验证 | 主线结果 |
|---|---|---|
| [#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202) | 日期缺省、显式日期与两类历史事件。Maven 插件限流后重跑 Backend CI 成功，PR 配对通过 | 已合并 `43ff5c9e4cfef2477b75359ae5cfa047fb0da19e`。合并推送的两项检查被后续主线任务取消 |
| [#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204) | 审阅材料计划批次绑定、旧批次候选和原始数量的可还原性。后端 CI 与配对均通过 | 已合并 `bd165ba58336ab485bf7ea9f40e99db4e4eb96fb`，其推送工作流被后续主线任务取消 |
| [#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207) | 审阅原更正及原流转行、作者和接收人。[后端 CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757465179) 与[配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45757465198) 均通过；在 #204 合并后的主线复验 48 项通过 | 已合并 `9a9540dea20a2f21110e549e52bec36d991091e2` |
| [#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208) | 审阅负责人、工程锁、历史交付与结算凭证及归档后重试。[后端 CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45758681207) 与[配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45758681251) 均通过 | 已合并 `337ed1a8158e58d5657cfec02d1694897c8aacb7` |
| [#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206) | 旧供货计划 10、首收约定应到 7 时，同键省略 7 曾被当作原请求。红测 `1ec8f2324b93ea65df55a41ed87ca1702fb6dad9`。修复基于原命令版本还原当时应到量，全部工程域测试 82 项通过 | 已复审，等待 head `dcb6bad42c4d18bbf2df7d86b322e2baa4a6d68e` 的远程验证 |
| [#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209) | 两个财务请求重叠时，后到请求读取不到原回执，并触发流水唯一约束。先取当前组织的工程锁，再读取幂等事件。两组 PostgreSQL 回归 49 项通过，已独立复审 | head `af5c7eed6b71bda10c762bc03293154aab5ec569`，等待远程验证 |

工程 PR 配对前端为 `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e`。节点 06 小程序提交 `f6625ecc23d2d317261b362b44f9b5b6070f89a3`，个人作品专项 49 项及 `npm run check` 通过。后端专项评审发现回收作品可能影响历史发布视频的播放，待修复后固定配对版本。节点 07 单独核对签约和净实收的去重口径，节点 08 单独核对设计工具预算与正式销售报价，节点 09 核对工程成本与账本。

### 节点 08 快速预算修订、验证与合并

项目负责人确认快速预算继续使用。初版后端 [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211) 与小程序 [#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) 均未合并，已转为草稿，在原 PR 内修订。验收覆盖门店及客户入口、旧链接、独立及项目预算生成、按现行规则取价和扣点、保存、重试与历史回读。AI 平面和立面生成仍按 DESIGN-02 退出。

修订后端 `90625c8c03e4578cc3ba203fef302a79b99cb2c7`、小程序 `8377058cdfa98ad1c8501144556da63ab08523c2` 已通过独立复核，两个 PR 已恢复正式评审。真实 PostgreSQL 和关联领域回归 252/252，覆盖独立和项目预算、正式报价、公司积分及幂等重试。小程序专项 189/189，完整 `npm run check` 通过。主包 1,965,839 字节，保留 131,313 字节。门店、资料库、客户草稿和原有方案结果均可进入预算，客户报价步骤分别提供快速预算和正式报价。

修订版本于 2026-11-26 13:52 先后合入 `backend-main` 和 `main`。两端 PR 的普通 CI 与配对检查均通过。[后端配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45762771888) · [小程序配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45762774193)

### 节点 03、06、07、09 的固定版本与范围

- #206 已合并至 `4e5acd3c83936843c2f05f9b555b51873e6a0e22`。[后端 CI](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45761116517) 和[配对](https://github.com/phlong026/zhongshu-ai-platform-v3/actions/runs/45761116535)通过。#209 配对已通过。其后端打包因 Maven Central 返回 429，已重跑失败作业。
- 节点 06 后端 [#216](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/216) `30c07c558e025955832106a9a9ec6a1af804a3d9`，小程序 [#218](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/218) `f6625ecc23d2d317261b362b44f9b5b6070f89a3`。回收个人作品后新发布会被阻止，已有发布继续播放并保留原客户来源。相同用例在旧实现返回 1114000005，修复后内容、权限和旧写入口共 36/36，小程序专项 49/49。
- 节点 07 后端 [#213](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/213) `715431ed719ddaab2eb156b2a8faa70bc25ccac2`，小程序 [#214](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/214) `b1a9f27531442678b40719e3977d69c68a1bfed5`。首轮后端 56/56、小程序专项 44/44。真实响应送入小程序得到两条发布、六次咨询、三名去重客户、一名有效签约客户、零名净实收客户。节点 06 与节点 07 组合编译，并运行新旧作品与统计四个 PostgreSQL 用例，均通过。多触点贡献分配和正式成交事件交业务确定。
- 节点 09 材料 [#215](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/215) `6131d986f77e1d1dd6dc6a2420dab5a8ee55f50a`，按材料原单核对已登记去向和合格到货量，专项 69/69。项目核算后端 [#219](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/219) `921f72ff1e4545577a8e464d9107862473ba82a7`，小程序 [#217](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/217) `424915543ebc24c1063809139c2f1dd3f3767d43`。录入劳务应付 100 元、付款 30 元后确认，随后原付款退回 15 元。真实响应送入门店和总部页面，当前待付 85 元，历史和总部最近确认均为 70 元，净现金流 -15 元，项目毛利 900 元。财务域 126/126，小程序相关 108/108。无应付余额字段的旧快照显示“—”。
