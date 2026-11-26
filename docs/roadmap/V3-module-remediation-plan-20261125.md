# 众墅之家 AI 赋能平台 V3 模块整改任务总表

> 任务定义包含 V1.1 与 V1.2 修订。
> 整理日期：2026-11-25（北京时间）  
> 仓库：phlong026/zhongshu-ai-platform-v3  
> 需求基准：2026-11-24 会议纪要、2026-11-25《众墅之家 V3 八步业务流程与系统调整报告》  
> 当前进度见本文件开头的任务清单。逐次验收详见[整改执行日志](V3-remediation-execution-log-20261125.md)。

## 全部整改任务的当前状态

截至 2026-11-26 18:01（北京时间），已核对小程序主线 `fc21af5d9a8b078bc69efbfc29d9233a8a45ba99` 和后端主线 `4c17ad0c10b19bd169a624c53c78d3a4c1165e11`。整改总表与执行日志在同一提交更新本节。每项完成后将状态、对应 PR、测试和剩余验收同时记入这两份文档。后文的阶段记录保留当时的检查结果。

### 2026-11-26 晚间业务决定与开发分工

项目负责人确认以下口径。此节记录开发依据，各项以对应 PR 的最终提交及联测结果登记交付。

- **公司积分**由平台超级管理员加到门店所属公司的共享积分池。接续 #245/#246，把线上新充值改为默认关闭的可配置开关，并保留原有代码。关闭时小程序隐藏充值入口，服务端拒绝新充值。已发起的订单仍可核对到账、查历史和办理退款。
- **第 04 步免带看**由经办人填写原因，再主动点击「跳过带看」。计入八步进度，页面和历史显示「已跳过」、原因、经办人和办理时间。实际带看继续记录工地和客户反馈。
- **施工合同及付款计划**由业务人员在签订施工合同时与客户确认并录入。可以一次付清或分多期，每期记录金额及付款日期或触发的工程节点，各期合计等于合同总额。合同和对应版本的计划随工程移交，签约前已实际到账的款项一并带入。工程按期联系客户并登记到款金额和凭据，支持部分到账。财务核对实际到账及入账，业务、工程按同一合同和期次回看已收、余款及付款条件。使用一份分期施工合同复验完整链路。签约后变更由谁发起、由谁确认仍待定。
- **第 07 步**只在正式施工合同签署后完成。单独签设计服务合同可归入设计委托档案，第 07 步继续待办。两种情况分别核对客户进度。第 06 步保留正式报价沟通判定，独立快速预算继续用于前期测算。
- **现场排期**由项目负责人负责部署，三家门店试点名单和时间延后提供。团队先推进开发、隔离前后端联测及演练准备。正式薪酬公式、公共费用分摊待用户提供后再实现。

### 13 个执行节点

| 节点 | 任务 | 当前状态 | 已验证及待办 | 对应 PR |
|---|---|---|---|---|
| 00 | 基线与任务冻结 | 已完成 | 两条主线、历史差异、复用评估和执行规则已登记。#175 | [#175](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/175) |
| 01 | 财务更正续办 | 已合并，待财务岗位验收 | 更正后丢失回执可续办，专项接入标准 CI。#171、#195 | [#171](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/171)、[#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) |
| 02 | 门店公开地址与总部权限 | 已合并，待部署和账号验收 | 独立公开地址、同品牌过滤及更换地址重新授权。#196、#201 | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196)、[#201](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/201) |
| 03 | 工程收款、到货与交付 | 节点 03 的代码及跟进 PR 已合并，待岗位现场验收 | 工程收款 #199/#202/#209、材料批次及更正 #204/#206/#207、交付结算 #208 和负责人核验 #210 已合并。#226 补齐原单分次补发，#244 使工程和老板首页随实际到货消项。 | [#199](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/199)、[#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202)、[#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204)、[#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206)、[#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207)、[#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208)、[#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209)、[#210](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/210)、[#226](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/226)、[#244](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/244) |
| 04 | 发布与客资 | 自动化回归通过，待真机 | 作品、真实发布登记、回填的链接以及新建与关联旧客户进入同一条自动化回归。#252 覆盖同一客户办完八步并开工。外部生成结果、发布渠道链接及现场账号待 B06。 | [#164](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/164)、[#168](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/168)、[#155](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/155)、[#84](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/84)、[#252](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/252) |
| 05 | 历史 PR 差异 | 差异核查已完成，真实渠道另验 | 逐项对照 #26、#27、#106、#107，核对主线替代接口、并发保证与退役流程。 | [#26](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/26)、[#27](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/27)、[#106](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/106)、[#107](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/107) |
| 06 | 我的作品 | 双端已合并，待原岗真机验收 | 个人回收、恢复、原稿和历史发布来源。后端 36 项、小程序 49 项通过。 | [#216](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/216)、[#218](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/218) |
| 07 | 内容获客统计 | 签约、净实收及页面口径均已合并 | 按客户编号去重，分别统计关联咨询客户中的有效签约和净实收。#227 的页面用语与接口口径一致。正式成交事件与多触点分配待业务确定。 | [#213](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/213)、[#214](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/214)、[#227](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/227) |
| 08 | 设计与快速预算 | 已合并，待门店真机验收 | 保留独立与项目快速预算、计价、扣点、保存、历史及导出。平面与立面 AI 生成已退出。后端 252 项、小程序专项 189 项及两端 CI 通过。 | [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211)、[#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) |
| 09 | 工程成本与项目账本 | 材料、项目账本、质量整改与施工延期已合并，待现场验收 | 实际收货、分次补发、领用和成本可追到原单。质量整改兼容旧版登记再分派。施工延期分别显示原工期和修订排期，保留原因、措施、恢复日和办理历史。撤岗、失效链接与弱网重试已复验。 | [#215](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/215)、[#219](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/219)、[#217](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/217)、[#225](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/225)、[#224](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/224)、[#226](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/226)、[#231](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/231)、[#232](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/232)、[#237](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/237)、[#242](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/242)、[#243](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/243)、[#244](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/244) |
| 10 | 客户、委托、合同至开工 | 跟进、正式报价、移交、进度区和附加资料已合并，其余表单分项验收 | 正式报价、八步附加资料、进度区已合并，旧版小程序编辑备注时也会保护已关联的报价。第 03 步、实测单引用、01/02/04 快捷录入与工程开工现场核对在固定配对上验收。 | [#221](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/221)、[#222](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/222)、[#249](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/249)、[#250](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/250)、[#251](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/251)、[#252](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/252)、[#253](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/253)、[#254](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/254)、[#255](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/255)、[#256](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/256)、[#257](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/257)、[#258](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/258)、[#259](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/259)、[#262](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/262)、[#263](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/263)、[#264](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/264)、[#265](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/265) |
| 11 | 绩效、公告、培训与公司额度 | 工资、绩效及公告培训已合并，公司积分方案已确认并接续开发 | 接续 #245 的超级管理员发公司积分及 #246 的小程序入口。充值代码保留，默认关闭新充值，历史订单、到账核对和退款继续可用。 | [#157](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/157)、[#160](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/160)、[#220](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/220)、[#228](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/228)、[#229](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/229)、[#230](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/230)、[#235](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/235)、[#240](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/240)、[#241](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/241)、[#245](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/245)、[#246](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/246) |
| 12 | 迁移、配对与上线 | 159 份制品迁移清单已合并，目标环境待验 | Windows 与 LF 检出按统一门槛验包。实际制品和已合入的清单共 159 份迁移、234 张表和 61 条序列，隔离 PostgreSQL 17 已执行全部正向 SQL 并与实物表和序列核对。31 张待补证或定界。 | [#149](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/149)、[#161](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/161)、[#163](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/163)、[#169](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/169)、[#223](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/223)、[#233](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/233)、[#236](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/236)、[#238](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/238)、[#253](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/253)、[#260](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/260) |

### 子任务与待补资料

| 任务编号 | 节点 | 当前状态 | 交付证据或尚待补齐的事项 |
|---|---|---|---|
| BASE-01、BASE-02、BASE-03、BASE-04、BASE-05、BASE-06 | 00 | 已完成 | 基线、PR、代码与数据库、失败线索、开源复用和执行日志均已登记，#175 已合并。 |
| AUTH-01 | 02 | 已合并，待总部岗位验收 | 总部跨品牌只读已落地。用真实总部管理员及财务账号核验。 |
| AUTH-02 | 02 | 已合并，待门店验收 | 同品牌安全地图与公开门店投影已落地。核对两品牌实际门店。 |
| AUTH-03、AUTH-04 | 02 | 已合并，待跨范围业务回归 | 列表、详情、搜索、附件、导出和多租户边界按岗位核对，保留总部与加盟商区别。 |
| AUTH-05 | 02、11 | 已合并，撤权按页面继续回归 | 会话失效、切岗及旧页面需真机核对。培训页面另在 OPS-04 修复。 |
| AUTH-06 | 02 | 地址及测试夹具已合并，待现场验收 | #196/#201 的地址与重新授权已合并。#200、#228 修复组织测试夹具。同一隔离 PostgreSQL 中反向运行 22 类 153 项通过，独立两轮各 17 项通过。上线迁移与现场抽验待办。 |
| CRM-01 | 10 | 客户八步及两项例外已确认，免带看正在开发 | 第 04 步允许主动填写原因并跳过，计入进度，保留实际经办人和时间。第 07 步只按正式施工合同已签署完成，单独签设计合同仍待办。其余六步沿用原业务依据。 |
| CRM-02、CRM-03 | 10 | 签约开工、进度区和八步附加资料已合并，待现场复核 | 签约和开工由合同与工程原记录驱动。列表、详情、八步进度和全部步骤已在微信开发者工具核对。八步独立追加文字、照片或附件，保存时登记真实经办人和服务端时间。办理前后均可追加，原有状态、版本及完成条件独立。 |
| CRM-04 | 10 | 同客户端到端已通过隔离配对 | 一件作品，两次咨询产生同一客户档案，用该客户的外部委托版本及自定义付款约定走完八步、施工签约、指定工程接收与实收后开工。已覆盖选本客户报价、上传外部报价、换版重新沟通和转交后重新授权。同时验过原实测单朝向及高差在外部委托和总部历史的回读。 |
| CRM-05 | 10、12 | 历史路径已回归，待历史样本 | 六类在途委托的原号、费用及版本可追溯，实际旧客户证据映射待环境验收。 |
| CRM-06 | 10 | 双端已合并，隔离配对通过 | #221/#222 按上海业务日期提醒。原生页面以 19 位原始客户编号打开跟进页，转交后新负责人收到唯一待办，办结后移除。 |
| CONTENT-01、CONTENT-02 | 04 | 已合并，自动化通过 | 发布回填、实际链接 A/B 核对、重复操作及回执丢失已回归，待手机弱网。 |
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
| ENG-01 | 03、10 | 合同与付款计划一并移交，联测续办 | 原有两期、三期、五期签约到开工已验证。本轮复验签前到账、按期工程部分收款、财务核对和三岗同源余款。签后变更发起及确认岗位待 B03。 |
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
| WALLET-02、WALLET-04、WALLET-05 | 11、12 | 默认关闭新充值，接续 #245/#246 | 超级管理员为门店所属公司加共享积分。小程序隐藏新充值入口，服务端同时控制新充值。存量订单到账核对、退款和对账沿用原记录。新版本须验证关闭与重新开启两种配置。 |
| WALLET-06 | 05 | 历史差异核查已完成 | #106/#107 关闭，当前公司额度沿用 #142/#143，旧模拟收银不进入正式渠道。 |
| QA-01 | 12 | 159 份制品迁移清单已合并，31 张表待审 | 清单随 #223/#233/#238/#260 合入。#253 新增附加资料与受控文件票据范围，实际制品 159 份 SQL、234 张表、61 条序列。203 条表归属已登记。27 张需现场证据，4 张需业务定界。 |
| QA-02 | 12 | 待 B05 | 待项目负责人提供清理与保留名单和恢复范围。 |
| QA-03 | 12 | 159 份脚本已在隔离库执行，目标环境待恢复演练 | Linux 迁移恢复专项 11 项。干净 PG17 实际顺序执行 159 份正向迁移，核对 234 张表、61 条序列和同公司客户组合外键。两个反向脚本均在写入并发中等待并拒绝撤销已有业务数据。现场备份的恢复时点和范围待 B05。 |
| QA-04 | 12 | 本轮已冻结两条功能主线，待目标环境发布验收 | 逐项配对的完整提交见下表，最后一次功能主线、组合回归和验包记录见本节末。部署目标和发布窗口随 B05、B06 核对。 |
| QA-05、QA-06 | 12 | 待真机与 B06 | 需公网 HTTPS 后端及文件接口、微信真机、三家门店账号和岗位。 |
| QA-07 | 12 | 已验证 CI 的迁移恢复与回滚，目标环境待演练 | 冻结和配对选择 21 项通过。固定案例的打包、迁移失败恢复和健康回滚已由 CI 演练。用部署现场备份验证 Flyway 升级、恢复及回滚仍需 B05。 |
| QA-08 | 12 | 已合并，最终功能组合通过 Windows 验包 | #169 的 2 MiB 上限与 128 KiB 预留继续执行。#236 使 Windows 和 LF 的包体计数一致。工程与绩效合入时主包 1,966,017 字节，余 131,135 字节，比仓库预留多 63 字节。 |
| B01 | 10 | 两项业务决定均已确认 | 有原因的主动跳过计入第 04 步进度，保留跳过事实。第 07 步只在正式施工合同已签署后完成。单独签设计合同可归入设计委托档案，施工签约待办。 |
| B02、B03、B04 | 10、11 | 两项公式暂缓，签后计划变更权限待定 | 业务签订施工合同时与客户确定同版付款计划。后续请补正式薪酬公式、公共费用分摊，以及签后由谁发起和确认计划变更。 |
| B05、B06 | 12 | 现场资料与排期待项目负责人 | 备份及恢复范围待补。用户负责部署，三家门店试点账号、岗位和时间由用户后续安排。 |

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
| 04、10 内容咨询到实际开工 | [#252](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/252) | `a7c4640a224ca0e25d2a5788131b5b796b769346` | `8276671c7c5f6025277fe1b96722a0941f1c71c3` | 真实作品保存、发布登记、回填外部链接和客资创建后，以第二次咨询关联同一旧档。单一客户走满八步、正式施工合同、指定工程、实收约定款和实际开工。原始 AI 结果及外部链接为隔离样本。在 Windows 和 PG17 运行完整移交套件及固定小程序的 18 项，全部通过且零跳过。开工后再次逐行核对两笔原咨询的来源、作品、发布链接。 |
| 10 第 06 步正式报价沟通 | [#250](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/250)、[#251](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/251) | `35e7eeec42ab40b095d55f3597843fe7cda27f30` | `7163de4e7a2f7f2a50c34c72e3efccd600c5db8c` | 接续侧聊原提交。可选本客户正式报价原单或上传外部正式报价，第一版 ¥12,345.00、第二版 ¥67,890.00 均以原单金额和版本写入报价沟通记录。换原件后清空旧沟通结果及日期并再次确认，保留六次历史。原生、Spring MVC 与 PG 在正式报价沟通、外部文件、转交及导出三组场景分别完成 21、10、15 次 HTTP 请求。样本为两张原单和一份外部 PDF。独立复核转交后原经办人失去选单、原单及导出权限，新经办人仍可办理。38 项后端专项及 2 项真实配对通过，小程序 33 组 1,622 项。 #250 已合并至 `5c2e2e1cef525bd8dbb5dfde3bdfc6ebffe3c067`。 #251 已合并至 `6917e1ea5a679882bb5c575da35e986782b80fa2`。 |
| 10 旧版小程序保护已关联正式报价 | [#262](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/262) | `c9801f6f769f58151355978ba535eaf64fe1b4bd` | `6917e1ea5a679882bb5c575da35e986782b80fa2` | 用报价上线前的小程序 `8276671c7c5f6025277fe1b96722a0941f1c71c3` 和现有版本分别保存。旧版本未提供报价编号时提示更新，原单、金额、备注、版本和历史均保留。新版本明确改用外部报价时清除既有沟通和日期，可上传外部 PDF 再次确认。丢回执原键重试只留一条历史。原头三类 PG 35 项，额外新旧包真实配对 1 项。 #262 已合并至 `4c17ad0c10b19bd169a624c53c78d3a4c1165e11`。 |
| 10 八步附加资料 | [#253](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/253)、[#254](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/254) | `9c25fdd46183ac335bfbb7372b9d3feeb2ae8839` | `d7c1373835d799f10db39bd312cc44f9f357815c` | 文字、图片、附件按同客户和实际步骤分别追加，登记上传人及服务端时间，能回查。源单、八步状态及修订独立。后端专项 20 项，前端 49 项、132 个 WXML 通过。组合验证三次取件逐字核对 PDF/PNG，覆盖未完成、已完成、未收到回执、跨客户和切岗。 微信开发者工具本地实际运行组件，30 笔请求，7 条路径通过。正式报价和附加资料合在同一代码树执行 42 项 PostgreSQL 测试，均通过。 #253 已合并至 `bec686f0a60c43a4cd90926d7a025148ca560ac0`。 #254 已合并至 `8276671c7c5f6025277fe1b96722a0941f1c71c3`。 |
| 10 定制方向的客户确认 | [#255](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/255)、[#256](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/256) | `ca055e3b4e9e3e883e847f5669da111724fa8af5` | `fb006b6edd23371b0456d3d0a85ab70fe420e5e1` | 同一客户的标准户型、外部定制方向或旧外部文件分别往返 4、17、8 次。定制方向可用文字，改方向须重新确认，总部能读取当前与历史原文。前端 33 组 1,633 项另加岗位 148 项。仓内固定配对已实际调用客户、其报价和登录人资料接口，覆盖附加资料的三次提交、两条记录、三次取原件。 合入旧包报价保护后的组合检查 37 项，零跳过。 |
| 10 引用本客户的历史实测单 | [#258](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/258)、[#259](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/259) | `ca11db9194e568a925af731e6cd81cd28a4b7c42` | `4c7b73fe77138d30365796eee2b6f345ce3d4a8e` | 可选本客户历史已核对实测版本，冻结日期、经办人、地址、尺寸和原附件。跨客户、未核对、已撤回文件均被拒。原始空名或超长的附件在委托端生成兼容旧版的展示名，仍可改备注和逐字取原件。客户转交后，旧经办人丢回执返回页面会重新核权，获准时同键重试，失权时清资料。配对 PG/MVC 44 项、原生委托 31 项通过。 最终工作树的 CRM、附加资料、外部委托和工程移交共 57 项通过，零跳过。 |
| 10 邀约、到店与带看录入 | [#257](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/257) | `399da49b7fc48e0874dcddda459cd0b4d31b9c54` | `58c82af23bfa2db04c85c230086a82b5fea97f0b` | 邀约快捷时间需经办人选定并确认。首次办理到店讲解可带入本客户需求和当前经办人，带看只可选本客户已完成记录中的工地。本次反馈均可编辑，原有记录继续按实际发生日显示。47 项专项，微信开发者工具 6 组交互、7 次读取、0 次自动写入。 新头在原生 390 像素视口逐步复核，每步各一个「附加资料」。第 03、06 步的原单凭据也可分别识别，补记只产生一次资料写入。 第 02 步预填逐一对照服务器当前本人编号、姓名以及客户编号和需求原文。 |
| 10 工程开工原单的现场核对 | [#263](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/263) | `4c17ad0c10b19bd169a624c53c78d3a4c1165e11` | `42c6131653b2c0d5e2070773af61dd2ca8e1a0ff` | 四通一平、业主确认情况和项目经理确认情况均可如实记录，保存在原工程 START 单，沿用实际开工日、经办人、凭据、工期和资金校验。请求在途切后台再返回，仍冻结原单和请求键。丢回执原键原体重试只生成一张原单和一条事件。原生页、Spring MVC 和 PG 20 次实际读取，付款阶段快照逐行一致，原 PDF 字节一致，进度由 7/8 更新至 8/8。旧工程只展示原有事实。 |
| 10 实测朝向与现场高差 | [#264](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/264)、[#265](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/265) | `9357467092360047a4de64847ee977a2d9944856` | `37cd3829f35ebd810f5adfd5b0d5d0779fbf65fe` | 在原测量记录填写真实朝向、高差或影响设计的说明，沿用既有事件和委托快照，可在当前、历史及总部授权视图回查。旧客户端省略字段会保留既有值，显式清除可区分。同一客户 24 次原生、Spring MVC、PostgreSQL 往返覆盖重新核对、引用新版本、重放、客户转交、成果与交接，交接时仍为 7/8。 |
| 11 学习中心 | [#220](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/220) | `4e5acd3c83936843c2f05f9b555b51873e6a0e22` | `ae41b60b51cd8f4501b8fff52c8f4d4cc97c73a6` | 用同一课程的真实响应验证可见、撤权及下架。 #220 已合并至 `2a65b0ed2cea3a95a29c29e939038652dc157455`。 |
| 11 工资实付 | [#229](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/229)、[#230](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/230) | `b4db0ab44bc7a99669bfff9209618fec7ff6150a` | `baa770343db49e7b7e1e9dc27475bfc7ff20e1cd` | 工资实付登记和支出退回都引用原单。100 元应发，30 元付款，退回 12 元，实付 18 元、未发 82 元。实际小程序发出的命令和 Spring 受理一致，重试仅保留一笔。含 #235 的同一提交上薪酬 16 项、运营 96 项通过。 #229 已合并至 `04e641f6a410ebf5bdcfbcc50e08f22da57aa41a`。 #230 已合并至 `962d6cdb7d0f9397c228115b2e55cdaca9a43fc3`。 |
| 11 运营测试夹具 | [#235](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/235) | `1a4ab5e7ec07cbe14aef0ce73c766321934a1381` | `cd9e485bf2fb65007b16b302525e01d6627a7ac6` | 总部、运营、公告和培训五个 PostgreSQL 集成测试类共用同一份用户表。复现总部先行时 update_time 缺失，在完整运营模块正、反序分别通过 96 项。 #235 已合并至 `d26456227985052127db1fd87c717189b5ce9e29`。 |
| 11 个人绩效周期复核 | [#241](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/241)、[#240](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/240) | `eed520cd390f82d4c5c317b0ad1c5619d0ad2f79` | `efcd868c55741fe5b45df62ef4405661dc2d6963` | 实际小程序与 Spring MVC/PostgreSQL 在三岗位配对 13 次读取、8 次写入。730 元的 17 位合同与客户编号准确。签约日从 11 月 3 日改到 4 日时退回，旧版仍保留 3 日。本人待审重提后旧复核返回 409。完整绩效模块 90 项、页面专项 14 项通过。 切岗时清理当前及历史详情、原单和延迟回执。在此头完整 npm 检查 33 组、1,600 项通过。 #241 已合并至 `5789a41658602d7de770453bcff51e82ce69be83`。 #240 已合并至 `52b28a8eeead81f7d4bace2c9c1873fedc9bcf38`。 |
| 11 公司积分及充值的登录回跳 | [#248](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/248) | `be8225583888fa1d0b5c1e3a6fdd14a9c5b06313` | `39b2b4da52eb0ff32261cc2157e8f694e8e95411` | 从现主线既有充值和日后可能采用的公司积分页返回登录，认证和岗位核对完成后回到发起页面。实际 Spring HTTP/PG 套餐读取只触发一次 GET，未生成订单。19 项登录回归和完整 npm 检查通过。已通过双项 CI 并合入。该修复保留现有充值功能。 #248 已合并至 `334396d12a447efbe642b0e0acc1b1a9f6d11a92`。 |
| 11 公司积分发放与充值入口 | [#245](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/245)、[#246](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/246) | `5b219ad6e8c3f9d825096e9e525d02f78abbfb41` | `46daa2372550fc8799af497ab0f35626a1c5337e` | 此对提交记录此前方案的回归基线。管理台 19 项、小程序 21 项和后端 33 项通过，含历史回调只入账一次。项目负责人已确认保留充值代码并默认关闭，修订版在原 PR 续办。 |
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

<details>
<summary>查看 31 张待审表和各自所需证据</summary>

| 表 | 待办 | 验收条件 |
|---|---|---|
| `design_billing_binding` | 取证 | 用 origin、org_id、ref_type/ref_id、subject 与 operation_id 交叉匹配实际执行及结算方。 |
| `design_prompt_receipt` | 取证 | 无 org_id。先用 job_id、attempt_no、operation_id 对回已审定设计命令。UNKNOWN 或已派发记录跟踪原执行方。 |
| `fr_commission_simulated_order` | 取证 | company_id、design_org_id、委托及原版本。作为历史模拟记录保留。真实结算需独立原凭据。 |
| `fr_content_cover_request` | 取证 | 公司、作者、request_key/hash 对封面生成。PROCESSING、UNCERTAIN、ARCHIVE_PENDING 须核服务商原单和唯一恢复者。 |
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
| `pf_map_brand` | 定界 | 按 tenant_id、已审定 B 门店及目录引用闭合。未关联门店或覆盖范围尚未认定的品牌由总部确认。 |
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

### 八步跟进的合并与配对记录

本次在小程序与后端现有主线上接续侧聊代码。原生进度区合入 #249，八步独立附加资料合入 #253/#254，客户正式报价合入 #250/#251，#262 补齐旧版小程序保存备注时对原单关联的保护。附加资料由后端登记真实办理人、任职和时间。微信开发者工具使用隔离数据，实测合同、开工源单与选填附件各自办理。客户转交后撤权、外部 PDF 原件、报价换版、第 03 步定制方向和第 05 步历史实测单已在对应固定提交上实测。具有写权限的经办人确认实际业务事实后，办理第 01 至 06 步。施工合同与实际开工由源单写入第 07、08 步。

同一件内容由两笔咨询关联同一客户，完整跑到签署施工合同、指定工程负责人、工程接收及实际开工。开工后由工程反查施工合同、客户，仍可读到两笔咨询原先的作品和发布记录。外部发布链接使用隔离样本。实际外部设计继续由经办人在对应客户委托下手动上传成果、施工图与专业审核依据。正式图纸和报价自动对接所需的软件方资料列在下表。

### 八步目前怎样办理

小程序的客户列表按钮、办理页标题、工作流入口及总部只读详情统一显示「客户跟进」。页内继续显示八项业务步骤和原有完成进度。文案提交 `5b8c40f3ab0b29392978b84c860284b89d628e35`，开发时核对的后端版本为 `5f5b176bf646939b008353fdd9234573654c9a21`。小程序完整检查 33 组 1,644 项通过，岗位首页 148 项通过，18 个包通过验包。

| 步骤 | 办理人操作及完成依据 | 当前验收 |
|---|---|---|
| 01 邀约 | 选择渠道与实际约定的日期、时间，再登记客户已答应到店。新录入默认上海当日，可按事实改回。 | 已回归已存日期、快捷未来时点、人工确认及旧草稿。#257 配对中。 |
| 02 到店 | 登记客户实际到店讲解，带出可核实的接待人和本客户需求，反馈可选可改。 | 已回归跨客户、切岗及延迟回复，均不覆盖已录入内容。#257 配对中。 |
| 03 户型或定制 | 选择标准户型或记录已确定的定制方向，登记客户确认。已有外部方案附件可沿用，定制意向可直接用文字。 | 已回归无附件的定制、换方向后重新确认、标准户型及旧外部文件，总部可查两版方向。#255/#256 配对中。 |
| 04 带看 | 实际带看填写工地与客户反馈。免带看由经办人填写原因后主动跳过。 | 实际带看可复用本客户已完成带看中的工地，也可手填，#257 已验证。免带看须计入进度并显示已跳过、原因、经办人与时间，正在开发。共用工地名册及其授权范围待门店提供。 |
| 05 实测 | 核对实际测量单。外部委托可引用本客户已核对的某一版事件，或继续手动上传合格原件。 | 实测朝向和影响设计的现场高差可随本版保存。固定来源、原件字节、跨客户拒绝、旧版本再次编辑及客户转交后返回页面均有回归。#258/#259 待最终评审。 |
| 06 正式报价 | 选择本客户原单或上传外部正式报价，登记本次真实沟通。原单金额与版本自动读取，换版后重新确认。 | #250/#251 已合并，原件、查看、下载、换版和转交后的读权限已验证。快速预算保留全部前期测算功能。 |
| 07 施工签约 | 正式施工合同已签署后，按签署原单自动完成。仅签设计合同可在设计委托档案留存。 | 已确认两种情形分别验收：设计合同单独归档时施工签约待办，正式施工合同签署后展示合同原单、签署凭据及对应付款计划。 |
| 08 实际开工 | 工程接收同一客户的外部成果版本、客户确认凭据、交底说明和付款约定，核对开工准备及应收，登记实际开工日和凭证。 | 已验证退回重交、单次建项和实际开工回写。正在工程原单补齐四通一平及业主、项目经理确认的现场录入。 |

八步都可在办理中及完成后另添文字、拍照或选图、上传附件，按步骤保留真实上传人和时间。第 07、08 步保留合同、工程原单入口。

### 需要集中补齐的资料与现场验收

| 事项 | 需要提供或确认的内容 | 用于关闭 |
|---|---|---|
| B01 八步业务流程 | 两项口径均已确认。主动跳过带看须填写原因，计入进度。仅签设计合同保留施工签约待办，正式施工合同已签署才完成第 07 步。 | CRM-01、CRM-02、CRM-03 |
| B02 绩效薪酬 | 项目负责人后续提供正式薪酬公式、适用月份、计提及退款处理规则，该规则实现暂缓。 | PERF-01、PERF-02、PERF-04 |
| B03 付款计划 | 签施工合同时由业务与客户确认，并将同版计划移交工程。签约后计划变更的发起人和确认人待业务负责人确定。 | ENG-01、FIN-01 |
| B04 成本分摊 | 公共费用分摊规则暂缓实现，待用户提供口径和能与项目直接成本区分的样例。 | FIN-04、FIN-06、FIN-07 |
| B05 迁移与恢复 | 项目负责人提供保留与清理名单、可恢复备份和恢复目标。按上表提供 27 张表所需现场证据，并对历史价格、品牌及未绑定门店目录等 4 张表明确归属。 | QA-01、QA-02、QA-03、QA-07 |
| B06 三家公司与真机 | 用户负责部署，三家门店的名单、时间、岗位、账号及真机由用户另排。上线前核验公网 HTTPS 后端及文件传输。现场核对存量订单到账、退款和共享积分。 | QA-05、QA-06、WALLET-02、WALLET-04、WALLET-05 |
| 外部设计图纸与报价接口 | 现阶段由用户导出并手动上传同一客户委托下的成果、施工图和专业审核依据。自动接口需软件方提供样本、接口说明和测试条件。 | DESIGN-04、ENTRUST-02、CRM-01 |
| 成交归因 | 业务负责人确认成交事件，以及同一客户有多个内容触点时的分配规则。现有签约和净实收指标可分别核对。 | CONTENT-05、CONTENT-07 |
| 尚未配置个人周期目标的门店 | 现有员工月目标由独立接口维护，旧目标页只读。若门店需在小程序从零开月，业务负责人需确认维护岗位和目标编辑入口。在此之前可办理已配置的周期。 | PERF-05 |
| 公司积分的补充方式 | 已确认为超级管理员向公司共享池加分，新充值默认关闭且保留开关。需要重启线上充值时再完善并验收渠道。当前接续 #245/#246，核对存量及启停两种状态。 | WALLET-01、WALLET-02、WALLET-03 |

### 客户八步完成标准与外部设计衔接（侧聊交接时的记录）

以下保存交接时依据文档提交 `13bdaab3877b341e6e79f1a1b2bc5339713830fa` 核定的口径与当时验证记录。实际接续状态以本节前方任务和配对表为准。目标是同一客户只录一次业务事实。面向加盟商内部的客户跟进页引用正式业务原单，显示进度、缺项和办理入口。2026 年 11 月 26 日核对了 11 月 24 日《众墅之家 AI 赋能平台上线前成果汇报》的腾讯会议回顾纪要、《合家美宅业务培训 2026·客户跟进》及现有项目流程。下表中尚未实现的操作为后续开发标准。

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

---

## 1. 整改目标与产品定位

将众墅之家 V3 收敛为供加盟商内部使用的经营管理系统，围绕客户跟进、工程协作、项目收支、人员绩效和总部品牌治理开展工作。

- AI 视频、文案及内容创作继续服务业务员获客；内容发布需要与客户登记、客户来源及成交结果形成可追溯关联。
- 标准户型灵感库保留为内部参考资料；实际设计、图纸、设计报价由外部设计软件完成，成果上传至客户及委托档案。
- 客户按正式八大流程推进；流程进度由服务端节点完成事实计算。
- 工程接收外部成果及正式付款计划，管理施工、材料、费用、收款、交付和结算。
- 每个项目建立独立账本，区分合同应收、实际收款、应付、实际付款、退款、费用、项目毛利与现金流。
- 业务与工程岗位支持薪酬规则、提成依据、人工复核及历史留痕；管理层未提供正式公式前，不擅自自动推导工资。
- 总部管理员、总部财务可在当前平台租户授权范围内跨品牌只读；加盟商仅查看本品牌安全地图及门店公开资料，不共享其他加盟商人员姓名、联系方式、客户、项目及财务数据。
- 公司内部 AI 视频额度及微信虚拟支付保留；设计费、工程款等实体资金在外部流转，系统只做业务登记和财务核算。
- 老板工作台当前以确定性经营指标为主，AI 摘要暂不上线。

## 2. 仓库与版本基线

本轮读取 GitHub 远程分支时的基线：

| 主线 | 分支 | SHA |
|---|---|---|
| 微信小程序 | main | ebe8edd47e860ea34ec02468f8a02768be07d5a5 |
| Java 后端及 Vue 管理后台 | backend-main | a2019dd4524d2bebea6e1f11af61eea8526d3b7c |

以上 SHA 是本次任务整理时读取到的版本基线。后续开发、合并或配对验收时必须重新读取实际 SHA，不得把本文档中的基线当作永久最新版本。

### 2.1 状态定义

- **已合并**：相关代码已进入对应目标主分支；不代表已部署或生产验收。
- **进行中**：存在开放 PR 或明确在研分支。
- **待核查**：代码可能已经被后续 PR 覆盖，需要 compare/diff 后决定是否还需开发。
- **待验收**：主体代码存在，但缺少规定的专项、配对、真机或业务验收。
- **待开发**：尚未找到满足业务要求的完整实现。
- **待业务资料**：需要管理层提供规则或名单，不能由研发自行假设。
- **阻断**：存在安全、资金或关键业务状态正确性风险，完成前不得作为正式上线能力。

## 3. 模块整改总览

| 模块 | 范围 | 原任务映射 | 当前总体判断 |
|---|---|---|---|
| M01 组织、品牌与总部权限 | 登录、岗位、品牌/门店数据隔离、总部只读、地图隐私 | R01-R04 | 主体已合并，需专项回归 |
| M02 客户管理与八大流程 | 客户档案、八节点、进度、提醒、历史迁移 | R05-R06 | 主体已合并，业务规则及边界待验收 |
| M03 AI 内容创作与客户获客 | 创作、发布、发布链接、客资归因、作品 | 增量任务 | 部分进行中，需闭环 |
| M04 灵感库与设计功能收敛 | 灵感资料、旧平面/立面生成退出、快速预算保留 | R16 | 范围已于 2026-11-26 确认，待配对验收 |
| M05 外部设计委托与合同 | 外部成果、报价登记、合同凭证、委托、工程交接 | R07-R10 | 主体已合并，需端到端验收 |
| M06 工程管理与施工协作 | 工程建档、付款计划、施工、材料、费用、收款、交付 | R10-R12及增量 | 有开放阻断修复 PR |
| M07 财务与项目独立账本 | 外部收付款登记、项目账本、成本、利润、结算 | R13-R14 | 主体已合并，需财务口径验收 |
| M08 业务与工程绩效 | 任务绩效、薪酬规则、提成、人工复核、发放登记 | R15 | 人工核算已实现，自动计算待评估 |
| M09 公告、培训与工作台导航 | 公告、培训、岗位导航、页面入口 | R17-R18 | 主体已合并，需页面回归 |
| M10 公司额度与虚拟支付 | 公司额度池、充值、回调、消耗、退款 | R19-R20 | 主体已有，开放支付 PR 需核对 |
| M11 数据迁移、配对验收与上线 | 迁移演练、测试数据、版本配对、真机及陪跑 | R21-R22 | 验收框架已有，真实环境验收待执行 |

## 4. 各模块子任务清单

### M01｜组织、品牌与总部权限

**历史实现：** R01-R04；后端 PR #120、#122、#133，小程序 PR #121、#123、#144，后续地图隐私修复 #165。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| AUTH-01 | 总部管理员、总部财务跨品牌只读工作台 | 待验收 | 总部真实任职、租户范围、只读边界 |
| AUTH-02 | 加盟商仅查看本品牌安全地图和公开门店资料 | 待验收 | 不返回其他品牌门店数据 |
| AUTH-03 | 列表、详情、搜索、导出、附件全链路越权检查 | 待验收 | 不能通过接口、导出或附件绕过权限 |
| AUTH-04 | 多品牌、多门店、跨租户访问隔离 | 待验收 | 品牌、公司、租户边界分别验证 |
| AUTH-05 | 身份切换、撤权、缓存和历史会话失效 | 待验收 | 撤权后旧会话及缓存不能继续访问 |

**已合并不得重复开发：**
- #120/#121：地图安全投影和小程序适配。
- #122/#123：停用匿名人员候选，改完整账号验证。
- #133/#144：总部跨品牌只读接口及工作台。
- #165：共享地图不返回门店内部详细地址。

**专项风险：** 地图公开资料仅允许安全门店显示名、品牌、行政区、公开地址及地图定位所需字段；不得泄露老板/员工姓名、手机号、微信、登录账号、用户 ID、内部备注及原始导入文本。必须验证真实接口响应、搜索、缓存和旧链接，不能只检查页面是否隐藏字段。

### M02｜客户管理与八大流程

**历史实现：** R05-R06；后端 PR #124、小程序 PR #125；集成候选 #161/#163。

八个节点：邀约进店、店面讲解、户型选择、带看工地、现场测量、别墅报价、合同签约、开工大吉。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| CRM-01 | 八节点必填字段、完成条件、跳步/回退规则 | 待业务资料 | 以管理层正式标准为准 |
| CRM-02 | 节点跳转、提前完成、退回、重开和更正 | 待验收 | 不允许无证据伪造完成；更正可追溯 |
| CRM-03 | 客户列表展示当前步骤、x/8、下一步及停留时间 | 待验收 | 列表与详情一致 |
| CRM-04 | 节点资料、客户、委托、合同和工程关联 | 待验收 | 附件归属、版本和客户一致 |
| CRM-05 | 历史客户流程证据映射与迁移 | 待验收 | 旧状态不自动伪造成八步完成 |
| CRM-06 | 超期跟进、下一次联系和责任人待办 | 待开发/核查 | 日期到期、转交、提醒及去重 |

**进度规则：** 当前代码按八节点等权重，每节点 12.5%，由服务端计算。会议配套报告将等权重作为建议规则，管理层尚需确认是否正式采用。不得把草稿保存、附件数量或重复点击计入完成率。八步跟进进度、工程施工进度、合同收款进度、项目结算状态必须分开。

### M03｜AI 内容创作与客户获客

| 子任务 | 内容 | 现有关联 PR | 当前状态 |
|---|---|---|---|
| CONTENT-01 | 发布结果回填发布链接并登记客资 | #164 | 已合并 |
| CONTENT-02 | 回读并校验真实保存链接，避免 A/B 链接错关联 | #168 | 进行中 |
| CONTENT-03 | 客资来源保留及待关联列表范围 | #155 | 已合并 |
| CONTENT-04 | AI 创作任务标题稳定化 | #84 | 开放 PR，待核对 CI/后端契约 |
| CONTENT-05 | 发布内容、客户来源、签约及成交统计 | 新任务 | 待开发 |
| CONTENT-06 | 我的作品后端权威 DTO、列表、删除及恢复能力核查 | 新任务 | 待核查 |

**专项验收：**
- 弱网、回执丢失、重复提交时，发布链接与客资必须一一对应。
- 只有确认服务端保存的链接与当前提交链接一致，才允许登记咨询。
- 一个客户可以关联多个触点，但成交客户不得按多个内容来源重复计数。
- 作品功能必须核实真实后端接口、权限和数据模型，不得仅凭前端 API wrapper 推断后端已经实现。

### M04｜灵感库与设计功能收敛

**历史实现：** 后端 PR #141、小程序 PR #145。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| DESIGN-01 | 灵感户型库检索、分类、详情和参考图片 | 待验收 | 资料真实来自后端 |
| DESIGN-02 | AI 平面/立面生成入口及写接口退出 | 部分完成 | 旧入口、深链和 API 均不能绕过 |
| DESIGN-03 | 保留快速预算 | 范围已确认 | 现有入口和旧链接可生成、保存、回读预算，覆盖独立预算及项目预算 |
| DESIGN-04 | 历史设计成果保留、预览和下载 | 待验收 | 历史数据不丢失 |
| DESIGN-05 | 旧设计深链、快捷入口、权限和待办收敛 | 待验收 | 不影响工程岗位原生 Tab |

2026-11-26 项目负责人确认保留快速预算，沿用 #145 的使用方式。节点 08 应验证门店、客户及灵感库的现有预算入口，旧深链、项目和独立预算的生成、保存与历史读取。AI 平面、立面生成的退出范围限定在对应的生成动作。灵感库继续提供资料检索。

### M05｜外部设计委托与合同

**历史实现：** 后端 PR #127、#134、#146；小程序 PR #137、#151；交接 PR #158/#159。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| ENTRUST-01 | 外部成果上传与客户档案关联 | 待验收 | 客户、委托、节点和资产引用一致 |
| ENTRUST-02 | 成果版本冻结及历史版本回读 | 待验收 | 修改后不覆盖已交接版本 |
| ENTRUST-03 | 外部报价登记与合同签约关联 | 待验收 | 报价金额、范围、日期、版本可追溯 |
| ENTRUST-04 | 取消内部预算及设计行政完成/下发前置 | 待验收 | 无内部预算、无设计行政仍可办理 |
| ENTRUST-05 | 工程接收、退回、补件和重新提交 | 待验收 | 重复接收不重复建工程 |
| ENTRUST-06 | 历史在途委托迁移和旧岗位待办清理 | 待验收 | 旧任务不被撤销岗位永久阻塞 |

### M06｜工程管理与施工协作

**历史实现：** R10-R12；后端 PR #128、#135，小程序 PR #132、#147；工程负责人交接 #153/#154；交付展示修复 #167。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| ENG-01 | 自定义付款节点与工程计划一致 | 已有代码，待验收 | 任意期数、比例、金额及版本继承 |
| ENG-02 | 工程交付前按有效付款节点核验实际净收款 | #166 进行中 | 逐期核对、退款后不足拦截 |
| ENG-03 | 交付后尾款不作为交付前置条件 | #166 覆盖 | 交付前应收款齐全即可交付 |
| ENG-04 | 材料采购、到货、领用与财务应付关联 | 待验收 | 不重复计费，来源可追溯 |
| ENG-05 | 劳务、差旅及其他工程费用归集 | 待验收 | 费用、应付和项目账本一致 |
| ENG-06 | 施工延期、质量整改和异常待办 | 待核查 | 责任人、时限、处理记录 |
| ENG-07 | 工程负责人变更及历史责任追溯 | 已有代码，待验收 | 授权、确认、移交记录完整 |

**#166 当前不得视为完成。** 需要 Backend CI、固定前后端 SHA 配对及真实测试环境业务场景通过后才允许合并。#167 已合并，仅需纳入工程凭据展示回归，不重复开发。

### M07｜财务与项目独立账本

**历史实现：** 后端 PR #136、#152；小程序 PR #139、#156。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| FIN-01 | 合同应收计划与实际收款关联 | 待验收 | 分期、分笔、退款和更正 |
| FIN-02 | 工程应付与实际付款关联 | 待验收 | 付款流水与应付单唯一关联 |
| FIN-03 | 退款、费用退回、冲销和历史修订 | 待验收 | 幂等、净额和审计历史 |
| FIN-04 | 客户前期设计费用归入正式项目账本 | 待核查 | 不伪造 project_id，不丢失来源 |
| FIN-05 | 材料、劳务、差旅和其他成本归集 | 待验收 | 费用来源唯一，不重复入账 |
| FIN-06 | 项目毛利、现金流、应收应付分开展示 | 已有基础，待验收 | 不混淆利润和现金结余 |
| FIN-07 | 项目结算、不可变快照和历史核算追溯 | 已有代码，待验收 | 历史版本可复核 |

**核算口径：**
- 项目毛利：合同确认收入减已确认项目直接费用。
- 资金净流入：净实收减净实付。
- 分摊后项目利润：项目毛利减按正式规则分摊的管理费用。

三者必须分开显示。公共管理费用分摊规则需要管理层提供，不得把项目毛利直接标成企业净利润。

### M08｜业务与工程绩效

**历史实现：** 后端 PR #157、小程序 PR #160。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| PERF-01 | 业务岗位底薪及提成规则 | 已有人工规则，待补公式 | 规则版本、生效月份 |
| PERF-02 | 工程岗位底薪及绩效规则 | 已有人工规则，待补公式 | 规则版本、生效月份 |
| PERF-03 | 项目提成与动态付款节点关联 | 待验收 | 退款、多人协作、节点触发 |
| PERF-04 | 结构化薪酬规则自动计算引擎 | 待开发 | 仅按已确认规则计算 |
| PERF-05 | 人工复核、申诉、归档和历史版本 | 已有代码，待验收 | 计算依据可追溯 |
| PERF-06 | 实际工资发放及财务登记 | 待核查 | 应发、已发、未发分开 |

**当前边界：** 现有代码是人工确认金额的薪酬核算模式（MANUAL_ONLY），不是完整自动工资计算。管理层未提供底薪、提成基数、比例、计提时间、退款冲回、多人员分配等规则前，不开发自动工资推算。

### M09｜公告、培训与工作台导航

**历史实现：** 后端 PR #115/#118，小程序 PR #116/#117。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| OPS-01 | 业务首页公告优先展示 | 待验收 | 重大公告优先，不挤占日常工作 |
| OPS-02 | “我的”页面独立公告模块 | 已有代码，待验收 | 列表、详情、未读/阅读状态 |
| OPS-03 | 总部公告发布、置顶、下架和品牌范围 | 已有代码，待验收 | 发布范围服务端控制 |
| OPS-04 | 培训入口下移、课程分类和学习记录 | 待验收 | 不影响日常工作入口 |
| OPS-05 | 多岗位原生 Tab 导航回归 | 待验收 | 工程、财务和兼岗入口不受影响 |
| OPS-06 | 旧公告入口、历史消息及深链兼容 | 待验收 | 不出现重复公告或失效跳转 |

### M10｜公司额度与虚拟支付

**历史实现：** R19-R20；后端 PR #119、#142，小程序 PR #143。仍有开放 PR #106/#107 需要差异核查。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| WALLET-01 | 公司开户自动幂等建额度池 | 已合并，待回归 | 重试不重复建池、不重置余额 |
| WALLET-02 | 公司额度微信虚拟支付真实充值 | 已有代码，待渠道验收 | 按腾讯虚拟支付政策验证 |
| WALLET-03 | 支付回调、查单和重复到账保护 | 待专项验收 | 一笔交易只入账一次 |
| WALLET-04 | 视频失败、取消、未知状态的额度/退款处理 | 待专项验收 | 生成失败退额度与支付退款分开 |
| WALLET-05 | 公司额度、使用消耗及退款对账 | 待验收 | 公司维度隔离、账实一致 |
| WALLET-06 | 对比 #106/#107 与 R20 #142/#143 | 进行中/待核查 | 不重复合并，不混淆 MOCK 与 REAL |

**支付边界：**
- 设计费、工程款、材料款等实体资金外部流转，系统只登记和核算。
- AI 视频公司额度及微信虚拟支付保留。
- MOCK 交易不得计入真实渠道收入；REAL 未完成渠道配置和验收前不得开启。
- 充值、扣减、退款和视频生成失败必须有可追溯的状态与幂等机制。

### M11｜数据迁移、配对验收与上线

**历史实现：** 后端 PR #149、#161，小程序 PR #163。

| 子任务 | 内容 | 当前状态 | 验收重点 |
|---|---|---|---|
| QA-01 | 历史数据迁移和业务证据映射 | 待环境验收 | 不伪造节点完成事实 |
| QA-02 | 测试数据清理名单确认 | 待业务资料 | 明确账号、公司、数据范围 |
| QA-03 | 清理演练、备份和恢复演练 | 已有方案，待执行 | 不误删真实客户、订单、余额 |
| QA-04 | 前后端固定 SHA 配对 | 需要更新基线 | 同一候选版本、接口契约一致 |
| QA-05 | 微信真机多岗位测试 | 待执行 | 冷启动、切换、返回、上传、弱网 |
| QA-06 | 三家公司端到端陪跑 | 待执行 | 每家公司独立数据和岗位 |
| QA-07 | 数据库迁移、部署、回滚验收 | 待执行 | Flyway 顺序、升级兼容、恢复路径 |

## 5. 2026-11-25 开放 PR 与历史分支核查

以下状态按 2026-11-25 本轮读取结果记录；执行时应再次刷新 GitHub 状态。

| PR | 当前状态 | 纳入模块/任务 | 后续动作 |
|---|---|---|---|
| #166 | 开放，目标 backend-main | M06 / ENG-02、ENG-03 | 继续修复、Backend CI、精确配对后合并 |
| #168 | 开放，目标 main | M03 / CONTENT-02 | 继续发布链接与客资关联防错 |
| #84 | 开放，目标 main | M03 / CONTENT-04 | 核对 CI、后端契约和作品功能范围 |
| #106 | 开放，目标 main | M10 / WALLET-06 | 与 #143 做差异比较 |
| #107 | 开放，目标 backend-main | M10 / WALLET-06 | 与 #142 做差异比较 |
| #26 | 开放，目标 main | M06 / 工程建档 | 与当前 main 对比，确认是否已被覆盖 |
| #27 | 开放，目标 backend-main | M06 / 工程建档、垫付并发 | 与当前 backend-main 对比，确认独有差异 |

已合并且不应重复开发：
- #164 发布链接回填及客资登记。
- #165 地图内部地址隐私修复。
- #167 工程交付接收人与业主签收展示区分。
- #124/#125 八步流程。
- #127/#134/#137/#146/#151 外部资料与合同。
- #128/#132/#135/#147 任意付款计划及继承。
- #133/#144 总部跨品牌只读。
- #136/#139 实体费用外部登记。
- #141/#145 设计能力收敛，快速预算继续提供。
- #142/#143 公司额度支付。
- #152/#156 项目独立核算。
- #157/#160 薪酬人工核算。
- #158/#159 工程交接与接收。
- #161/#163 配对候选与集成。

注意：PR 已合并不等于已部署或业务验收。以上只表示代码已进入对应目标分支。

## 6. 模块依赖关系与实施顺序

业务依赖关系：

- M01 组织与权限 → M02 客户八步、M09 总部与公告。
- M02 客户八步 → M03 内容获客归因、M05 外部委托与合同。
- M05 外部委托与合同 → M06 工程管理。
- M06 工程管理 → M07 财务账本。
- M07 财务账本 → M08 绩效薪酬。
- M04 灵感库与设计收敛 → M05 外部委托。
- M10 公司额度与虚拟支付 → M03 内容创作。
- M01、M02、M06、M07、M08 → M11 最终配对和上线验收。

### 执行批次

**第一批：当前在研问题和阻断项**
1. M06：继续完成 #166 工程交付付款门禁。
2. M03：继续完成 #168 发布链接与客资关联防错。
3. M01：执行总部、品牌、门店全链路权限回归。
4. M10：核对 #106/#107 与 R20 真实虚拟支付实现。
5. 核查 #84、#26/#27 是否存在未被主干覆盖的独有差异。

**第二批：业务主链路验收**
1. M02：八大流程规则、节点状态、历史数据。
2. M05：外部成果、合同、委托与工程交接。
3. M06：材料、施工、费用及收款完整链路。
4. M07：项目账本、成本归集和利润口径。

**第三批：经营管理完善**
1. M08：薪酬规则、人工复核及自动计算需求。
2. M04 退出旧平面与立面生成入口及写接口，验证快速预算的完整办理链。
3. M09：公告、培训和多岗位导航回归。
4. M03：内容获客和成交经营指标闭环。

**第四批：整体上线验收**
1. M11：迁移与数据清理演练。
2. 冻结最新前后端配对 SHA。
3. 微信真机多岗位测试。
4. 三家公司端到端陪跑。

上述批次允许无依赖任务并行，但任何模块合并前必须完成其依赖任务所需的契约与集成验证。

## 7. 统一开发、评审、PR 与合并规范

### 7.1 任务层级

- 模块：M01-M11。
- 子任务：AUTH-01、ENG-02 等。
- 每个子任务：独立开发、独立测试、独立专项评审、独立 Commit。
- 每个模块：形成模块整改 PR；如果同时涉及小程序和后端，建立配对 PR，并在模块总任务中统一追踪。

### 7.2 分支与 PR

建议分支命名：
- module/auth-remediation-YYYYMMDD
- module/engineering-remediation-YYYYMMDD
- module/content-remediation-YYYYMMDD

由于仓库存在 main 和 backend-main 两条主线：
- 小程序改动只能进入 main。
- Java 后端/Vue 管理后台改动只能进入 backend-main。
- 跨端模块应建立配对 PR，记录双方完整 SHA。
- 不得把不相关的前后端 CI 绿灯当作配对验收。

### 7.3 每个子任务的完成门槛

1. 现有代码与数据库基线审计。
2. 开源复用评估；优先复用当前 Yudao/RuoYi 底座，不为重构而引入新系统。
3. 明确输入、处理规则、输出、异常路径和数据权限。
4. 先编写失败测试，再实现。
5. 独立开发及专项评审。
6. 单元、集成、权限和异常场景测试。
7. 形成独立 Commit。
8. 更新模块整改台账中的任务状态及证据。

### 7.4 模块 PR 合并门槛

- 模块范围内所有阻断子任务已完成。
- 每个子任务有专项评审记录。
- 前后端 DTO、接口、权限及状态流转配对一致。
- 数据库迁移经过升级、已有数据兼容及恢复路径验证。
- PR CI 全绿。
- 合并后对应主分支 CI 重新通过。
- 记录合并 SHA、配对 SHA 和业务验收结果。
- 代码合并不等于生产部署；部署、迁移、真机和业务陪跑需单独记录。

## 8. 必须补齐的业务资料

| 编号 | 资料 | 影响模块 | 责任方 |
|---|---|---|---|
| B01 | 八大流程每一步的必填资料、完成/跳步/回退规则 | M02 | 管理层/业务负责人 |
| B02 | 业务与工程岗位底薪、提成、考核公式 | M08 | 管理层 |
| B03 | 付款计划首次填写、修改、确认及变更权限 | M05/M06 | 管理层/财务 |
| B04 | 项目毛利、公共管理费用分摊规则 | M07 | 管理层/财务 |
| B05 | 测试数据清理名单、保留名单和恢复要求 | M11 | 项目负责人 |
| B06 | 三家陪跑公司、测试账号及岗位名单 | M11 | 项目负责人 |

未取得正式资料前，研发不得自行设定薪酬比例、付款审批权限、流程权重或数据清理范围。

## 9. 三家公司端到端验收场景

每家公司配置老板、业务员、工程负责人、财务等岗位，并使用独立测试数据；另增加同品牌多门店和第二租户测试数据验证边界。

| 场景 | 操作 | 预期结果 |
|---|---|---|
| 登录与岗位 | 多岗位登录、切换、撤权 | 进入正确工作台，撤权立即生效 |
| 品牌隔离 | A 品牌访问 B 品牌业务数据 | 拒绝访问 |
| 地图 | 同品牌查看门店 | 仅返回安全公开资料 |
| 客户 | 登记客户、分配、跟进 | 归属及来源正确 |
| 八步 | 完成节点、退回、重开 | 进度与证据一致 |
| 合同 | 上传签约凭证及付款计划 | 正式合同状态正确 |
| 外部委托 | 上传成果、版本及客户确认 | 客户档案与委托一致 |
| 工程接收 | 工程接收或退回交接包 | 不重复建工程 |
| 施工 | 开工、施工节点、材料和费用 | 工程状态与记录一致 |
| 收款付款 | 分期收款、分笔付款、退款 | 应收应付和净额正确 |
| 交付 | 自定义付款计划下核验交付 | 交付门禁符合冻结合同 |
| 项目核算 | 汇总收入、成本、现金流 | 不重复入账，口径分开 |
| 绩效 | 业务和工程薪酬核算 | 金额及依据可追溯 |
| 额度 | 充值、消耗、失败、取消、退款 | 公司额度隔离且只入账一次 |
| 总部 | 管理员和财务跨品牌查看 | 只读范围正确，不越租户 |

## 10. 整改台账更新规则

后续每次开发进展都更新对应模块及子任务，不重复创建同一问题。

建议记录字段：

| 字段 | 说明 |
|---|---|
| 模块编号 | M01-M11 |
| 子任务编号 | AUTH-01、ENG-02 等 |
| 问题及业务影响 | 为什么需要整改 |
| 代码位置 | 前端、后端、数据库文件 |
| 关联 PR | 已有 PR、配对 PR |
| 分支 | 当前开发分支 |
| Commit SHA | 独立提交记录 |
| 专项评审 | 通过/未通过及意见 |
| CI | 检查结果 |
| 配对 SHA | 前后端精确版本 |
| 数据库迁移 | 迁移文件、升级及恢复状态 |
| 业务验收 | 场景、结果、证据 |
| 最终状态 | 待开发/进行中/待验收/已完成 |

**状态更新原则：**
- PR 合并后改为“已合并，待业务验收”，不能直接改为“已完成”。
- 只有代码、配对、数据库、测试及业务验收全部通过，才标记“已完成”。
- 新发现的问题先归入已有模块；确实无法归类时再增加模块。
- 已合并 PR 不重复开发；开放 PR 先做差异比较，再决定继续、拆分或关闭。
- 所有变更保留历史，不覆盖旧的验收记录。

## 11. 本文档本次写入说明

本文件是 V3 整改主台账，基于 2026-11-25 读取的 GitHub 状态整理。本文档本身不修改业务代码，不执行数据库迁移，不代表生产部署或真机验收。

后续模块整改应以本文件为统一索引，并在每个模块 PR 中引用对应模块和子任务编号。


---

# V1.1 更新记录（2026-11-25）

本节为 V1.1 相对 V1.0 的状态校正与新增任务。若本节与前文 V1.0 快照中的旧状态冲突，以本节为准。原始任务编号和历史 PR 映射保留，用于追溯，不代表仍需重新开发。

## A. 最新 PR 状态校正

本次重新读取 GitHub 后，当前分支基线：

| 主线 | 分支 | 当前 SHA |
|---|---|---|
| 小程序 | main | 1aa4cbae0487c10fca029d18533d56a2981328f2 |
| Java 后端/Vue 管理后台 | backend-main | 762efc68de328859ab593a88a27f78b968f0b5b9 |

PR 状态：

| PR | 最新状态 | V1.1 处理 |
|---|---|---|
| #166 工程交付实际付款节点核验 | 已合并至 backend-main | ENG-02/03 改为“代码已合并，待业务回归” |
| #167 工程接收人与业主签收展示区分 | 已合并至 main | 纳入工程交付凭据回归，不重复开发 |
| #168 发布链接与客资关联防错 | 已合并至 main | CONTENT-02 改为“代码已合并，待业务回归” |
| #169 小程序主包预留空间修复 | 已合并至 main | 新增 QA-08 持续回归 |
| #170 整改任务总表 | 已合并至 main | 本文件升级 V1.1 |
| #171 财务退款更正恢复 | 开放，目标 main | FIN-03A，当前唯一开放 PR，等待 CI/配对验收 |
| #84 AI 创作任务标题稳定化 | 已合并至 main | CONTENT-04 改为“代码已合并，待业务回归” |
| #106 MOCK 支付小程序 | 已关闭、未合并 | 作为历史方案退役，不再推进合并 |
| #107 全局 MOCK/REAL 支付基础 | 已关闭、未合并 | 作为历史方案退役，不再推进合并 |
| #26 旧工程建档小程序方案 | 已关闭、未合并 | 由当前合同建档能力替代；只核查覆盖差异 |
| #27 旧工程建档/垫付并发方案 | 已关闭、未合并 | 不重新合并旧分支；并发能力纳入财务专项回归 |

截至本次复核，开放 PR 列表仅发现 #171。此状态为 2026-11-25 快照，后续执行须重新读取 GitHub。

## B. V1.1 模块状态调整

### M01｜组织、品牌与总部权限

原 AUTH-01 至 AUTH-05 保留，但状态由“待开发”统一调整为“已合并，待专项回归”，适用范围包括总部全品牌只读、地图品牌过滤、匿名账号候选停用及共享地图地址隐私修复。

新增：

| 子任务 | 内容 | 状态 | 验收重点 |
|---|---|---|---|
| AUTH-06 | 门店公开地址独立维护与授权展示 | 待开发/核查 | 不直接暴露内部地址；公开地址需独立字段、明确授权；地图/搜索/详情一致 |

代码复核发现，当前后端地图响应中的 publicAddress 被固定为空字符串，内部地址泄露风险已收敛，但“同品牌查看公开门店地址”的正向能力尚不能据此认定完成。AUTH-06 应先核对现有公开地址字段、后台维护入口及授权逻辑，再决定最小改动方案。

### M02｜客户管理与八大流程

R05/R06 的模型、命令及小程序入口已合并。子任务状态调整如下：

- CRM-01：待业务资料。八节点必填字段、完成条件、跳步/回退规则仍以管理层正式标准为准。
- CRM-02 至 CRM-05：代码已合并，待业务回归及历史数据验收。
- CRM-06：超期跟进、责任人提醒仍需核查；确认无实现后再独立开发。

八步等权 12.5% 仍是当前实现/建议口径，不得在管理层确认前固化为不可调整的永久规则。

### M03｜AI 内容创作与客户获客

| 子任务 | 内容 | 最新状态 |
|---|---|---|
| CONTENT-01 | 发布结果回填发布链接并登记客资（#164） | 已合并，待业务回归 |
| CONTENT-02 | 实际保存链接校验、防止 A/B 错关联（#168） | 已合并，待弱网/回执丢失回归 |
| CONTENT-03 | 客资来源保留及待关联列表范围（#155） | 已合并，待回归 |
| CONTENT-04 | AI 创作任务标题稳定化（#84） | 已合并，待回归 |
| CONTENT-06A | 我的作品列表与后端权威 DTO/接口核查 | 待核查 |
| CONTENT-06B | 作品删除、恢复和权限 | 待核查；不得猜测接口字段 |
| CONTENT-06C | 任务中心结果保存到我的作品 | 待业务验收 |
| CONTENT-07 | 内容来源、客户、签约及成交统计 | 待开发 |

#84 已合并仅代表任务标题相关代码进入 main，不代表“我的作品”功能整体完成。PR 说明指出作品后端权威 DTO/列表和删除接口仍需核实。先读取当前后端实现，再决定开发范围。

### M04｜灵感库与设计功能收敛

- DESIGN-01 灵感户型库：保留，代码已存在，待资料检索及权限回归。
- DESIGN-02 AI 平面/立面生成：按会议定位退出正式业务入口和写链路。
- DESIGN-03 的早期规划曾提出剥离快速预算。2026-11-26 项目负责人明确保留此功能，执行时按现有入口、深链、服务端生成、保存和历史读取做正向回归。
- DESIGN-04 历史成果：保留只读、预览、下载及客户档案追溯。
- DESIGN-05 旧设计深链、快捷入口和旧岗位待办：待专项收敛。
- 业务员导航调整不得删除工程岗位使用的原生 Tab 或工程页面。

### M05｜外部设计委托与合同

R07-R10 相关后端/小程序 PR 已合并。ENTRUST-01 至 ENTRUST-06 改为“代码已合并，待端到端业务验收”，重点验证：无内部预算、无设计行政账号的条件下，业务员能否完成外部成果上传、版本确认、报价登记、合同签约、工程提交/退回及工程接收。历史在途委托和旧岗位待办仍需迁移演练。

### M06｜工程管理与施工协作

- ENG-01：自定义付款计划与继承，代码已合并，待 2/3/5 期业务回归。
- ENG-02：#166 已合并，待实际净收款/退款回归。
- ENG-03：#166 已覆盖交付后尾款不阻断交付，待回归。
- ENG-04 至 ENG-06：材料、费用、施工异常仍需核查实际页面、接口和账本归集，不得仅凭模块存在认定完成。
- ENG-07：负责人变更 #153/#154 已合并，待权限、离岗和历史责任回归。
- #167 已合并，接收人与业主签收材料区分纳入交付凭据回归。

至少执行以下场景：五期付款计划逐期核验、退款后净额不足拦截、补齐后放行、交付后尾款不拦截、历史无统一合同工程兼容、接收人与业主签收材料区分。

### M07｜财务与项目独立账本

新增 FIN-03A：

| 子任务 | 内容 | 关联 PR | 状态 |
|---|---|---|---|
| FIN-03A | 退款/支出退回作废成功但回执丢失后的继续重登 | #171 | 开放，等待 CI/配对验收 |

#171 当前只修改小程序财务工作台的恢复入口和表单续办逻辑，不应视为后端退款账务链路整体重写。验收需覆盖：作废成功回执丢失、重新打开继续重登、普通作废不进入队列、已有替代单不重复展示、无权限用户不可操作、切换岗位后的迟到响应隔离、退款与支出退回分别恢复、不得二次作废。

其他 FIN-01 至 FIN-07 保留为“代码已合并，待账务业务回归/核查”，尤其检查客户前期费用是否归入正式项目账本、材料/劳务成本是否重复计入、现金净额与项目毛利是否分开。新增 FIN-08：员工垫付、报销、退款、归还和作废并发一致性回归；旧 PR #27 不重新合并，当前主干已有相关锁定与 PostgreSQL 并发集成测试，需在当前版本回归。

### M08｜业务与工程绩效

R15 后端 #157、小程序 #160 已合并。人工核算能力进入业务验收。PERF-04 自动计算引擎仍为待开发，但必须等待管理层提供结构化底薪、提成基数、比例、计提时点、退款冲回、多人分配及生效版本规则。规则未确认前不得虚构自动工资。PERF-06 实际工资发放登记需核对是否已有完整业务链路。

### M09｜公告、培训与工作台导航

R17/R18 相关 PR #115-#118 已合并。OPS-01 至 OPS-06 由开发状态调整为页面/权限/多岗位导航回归；重点验证公告品牌范围、置顶/下架、公告列表更新、培训入口下移以及工程/财务岗位原生 Tab 不受影响。

### M10｜公司额度与虚拟支付

R19/R20 后端 #119/#142、小程序 #143 已合并。#106/#107 已关闭未合并，不再作为待合并任务；将其记录为历史 MOCK/REAL 方案退役。

WALLET-01 至 WALLET-05 进入真实渠道及账务专项验收：公司开户幂等建池、微信虚拟支付真实充值、重复回调/查单只入账一次、视频失败/取消/未知状态额度恢复、公司切换隔离及额度消耗对账。MOCK 仅可用于测试，不得计入真实渠道收入；REAL 未完成渠道配置、服务端验证及微信真机验收前不得视为已开通。

### M11｜数据迁移、配对验收与上线

R21/R22 的 #149/#161/#163 已合并，#169 已修复小程序主包预留空间。更新：

- QA-01 至 QA-07：从“待开发”转为“待环境执行/业务验收”。
- QA-04：必须重新冻结最新 main/backend-main 精确 SHA；旧 R22 配对 SHA 不能直接作为当前发布基线。
- QA-05：微信真机多岗位测试待执行。
- QA-06：三家公司端到端陪跑待执行。
- QA-07：数据库迁移、部署、回滚仍需环境验收。
- QA-08：小程序主包体积与预留空间持续回归，#169 已合并但每轮新增功能仍需检查。

## C. 历史开放 PR 退役及差异核查

| PR | 最新状态 | 处理 |
|---|---|---|
| #26 | 已关闭、未合并 | 旧工程建档接口方案由当前合同建档能力替代；保留差异核查记录，不重新合并 |
| #27 | 已关闭、未合并 | 旧工程/垫付并发方案不重新合并；对照当前主干测试覆盖 |
| #84 | 已合并 | 任务标题能力完成；作品后端功能另列 CONTENT-06 |
| #106/#107 | 已关闭、未合并 | 旧全局 MOCK/REAL 方案退役；以 R20 #142/#143 当前真实虚拟支付链路为准 |
| #166/#168 | 已合并 | 转为业务回归，不再列开放开发 |
| #171 | 开放 | FIN-03A，当前唯一开放 PR |

## D. V1.1 执行优先级调整

**第一批：当前开放/高风险**
1. 完成 #171 的 CI、代码评审、前后端配对及财务更正恢复专项验收。
2. 对 AUTH-06 门店公开地址进行基线核查，明确公开字段与维护入口。
3. 对 ENG-02/03、CONTENT-02 执行合并后业务回归。
4. 核查 #26/#27/#106/#107 关闭未合并后的主干覆盖情况，形成退役记录。

**第二批：剩余功能缺口**
1. CONTENT-06 我的作品后端能力核查与补齐。
2. CONTENT-07 内容获客到签约成交统计。
3. DESIGN-02/03/05 退出旧平面与立面生成，验证快速预算和历史成果。
4. FIN-04 客户前期费用项目归集、ENG-04/05 工程材料与成本财务归集。

**第三批：全模块业务验收**
1. M01/M02/M05/M06/M07/M08/M09/M10 按岗位和业务链路回归。
2. 结构化薪酬规则等待管理层资料。
3. 微信虚拟支付真实渠道与真机验收。
4. 数据迁移、测试数据清理及恢复演练。

**第四批：发布候选验收**
1. 更新并冻结前后端配对 SHA。
2. 主分支 CI 全绿。
3. 数据库迁移、部署和回滚演练。
4. 三家公司全流程陪跑并记录问题闭环。

## E. 状态口径升级

V1.1 统一使用以下状态：
- **已合并，待业务验收**：代码已进入目标主分支，但尚未通过完整业务场景。
- **进行中**：有当前开放 PR/分支。
- **待开发**：确认当前主干没有满足要求的实现。
- **待核查**：先读取当前代码、测试和接口契约，再决定是否开发。
- **待业务资料**：依赖管理层正式规则。
- **已完成**：代码、专项评审、CI、前后端配对、数据库及业务验收全部通过。
- **历史方案退役**：旧 PR/分支不再推进，保留追溯记录。

不得将“PR 已合并”直接等同“模块已完成”，也不得仅凭历史开放 PR 判断当前主干缺少功能。


---

# V1.2 状态更新（2026-11-26）

本节记录 V1.2 启动时的合并状态与执行顺序。此后各任务进度见文首“全部整改任务的当前状态”。

## 1. 当前代码基线

| 主线 | 当前 SHA |
|---|---|
| 小程序 `main` | `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` |
| Java 后端/Vue 管理后台 `backend-main` | `11014a5cf936166b71134bf40f838ad93b04ce43` |

## 2. 最新 PR 状态

| PR | 任务 | 当前状态 | 处理结果 |
|---|---|---|---|
| #194 | 客户列表分页一致性 | 已合并至 main | 纳入客户列表回归，不重复开发 |
| #195 | FIN-03A-CI-01 财务更正专项测试接入 | 已合并至 main | 专项脚本进入标准 `npm run check` |
| #196 | AUTH-06 公开门店地址与授权 | 已合并至 backend-main | 独立公开地址字段、总部维护、授权投影及隔离测试已合并 |

## 3. AUTH-06 最新状态

AUTH-06 已由 PR #196 完成代码实现和合并，新增迁移：

`V20261126.1001__franchise_public_map_address.sql`

实现边界：
- 公开地址独立存储，默认未授权，不从内部地址或业主住宅地址自动回填。
- 总部维护页必须明确勾选公开展示授权；撤销授权时清空公开地址。
- 仅 FRANCHISE 档案可以授权；服务端地图投影再次校验档案类型和授权状态。
- 加盟商地图仍按品牌过滤；总部查询仍受租户边界约束。
- 现有小程序地图列表、详情和搜索已经支持 `publicAddress`，不重复开发地图组件。

验收结果：
- Backend CI run `45745242295` success。
- Paired release rehearsal run `45745321948` success。
- 配对 SHA：前端 `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f`；后端 PR head `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58`。
- 管理台 116 项测试及构建通过。
- CI 隔离环境已执行 Flyway 迁移。

**当前状态：已合并，待生产迁移部署及总部真实账号业务验收。** 不得将 CI 隔离环境迁移等同生产迁移。

## 4. FIN-03A 最新状态

PR #195 已合并。专项回归脚本已接入标准检查链，Frontend CI、Backend CI 和前后端配对验证均通过。财务更正恢复仍需财务岗位真实业务验收；未操作真实账务。

## 5. 节点 03 新增专项审计

在现有 ENG-02/03/04 代码基础上，新增以下幂等完整性检查。每项必须先提交失败测试，再实现，并记录旧幂等事件兼容方案：

| 子任务 | 范围 | 重点问题 |
|---|---|---|
| ENG-IDEM-01 | 收款登记 | 发生日期、凭证资产、项目归属是否纳入幂等请求指纹 |
| ENG-IDEM-02 | 材料计划 | 材料名称、规格、单位、数量是否完整纳入请求指纹 |
| ENG-IDEM-03 | 到货、补发及质量处理 | 预计数量、交易号、凭证、日期、备注是否完整纳入 |
| ENG-IDEM-04 | 到货更正及材料流转 | 更正原因、领用备注、接收人是否纳入 |
| ENG-IDEM-05 | 工程交付与结算 | 交付备注、结算凭证资产是否纳入 |

这批为代码审计初步发现，需以失败测试确认影响；不得简单扩展 payload 后破坏历史合法重试。节点 03 仍需完整覆盖：五期付款计划净额核验、退款后重新拦截、待补到货不计入实际到货、实际到货及质量处理、交付接收人与业主签收凭据区分、交付后尾款不阻断交付。

## 6. 后续执行顺序

1. 节点 03：完成工程收款、材料计划/到货、交付与结算的幂等专项及业务回归。
2. 节点 04：发布链接与客资关联验收。
3. 节点 05：历史 PR 差异核查。
4. 节点 06：我的作品功能补齐。
5. 节点 07：内容获客成交统计。
6. 节点 08 退出 AI 平面与立面生成，保留快速预算。
7. 节点 09：工程成本与财务账本。
8. 节点 10：客户、委托、合同、工程全链路。
9. 节点 11：绩效、公告、导航及公司额度。
10. 节点 12：数据迁移与正式上线验收。

所有节点仍执行独立子任务、失败测试优先、专项评审、独立 Commit、模块 PR、CI、精确前后端配对和合并后回归。生产部署、真实账号验收及数据迁移必须单独记录。

## 7. 节点 03 分批开发记录（2026-11-26）

| 子任务 | 已有结果 | 待办 |
|---|---|---|
| ENG-IDEM-01 收款登记 | #199、#202、#209 已合并。并发请求取回同一回执，后端 49 项回归及两项 PR 检查通过 | 工程和财务岗位核对收款 |
| ENG-IDEM-02 材料计划 | #204 已评审并合并。核验原批次、明细和备注 | 历史单无法唯一定位时由工程岗位核对原单 |
| ENG-IDEM-03 到货、补发、质检 | #206 已根据独立评审修复旧首收漏填应到量。工程域 82 项、后端 CI 和配对均通过，已合并 | 用历史供货单复核实收、补发及质检 |
| ENG-IDEM-04 到货更正、材料流转 | #207 已评审并合并。按 #204 合入后的主线另跑两组测试，48 项通过 | 岗位核对原更正单、领用记录及接收人 |
| ENG-IDEM-05 交付、结算 | #208 已评审并合并。隔离库 42 项通过，两项 PR 检查通过 | 现场核对交付接收人、业主签收凭据及财务结算凭证 |

五个后端 PR 的提交在本地临时合并后共同运行工程门禁和材料集成测试，51 项通过，0 跳过。独立 PR 的评审、正式合并和现场业务验收分别跟踪。

### 集中补充的业务资料与验收

- 财务岗位实际办理更正恢复，总部真实账号验证公开地址的授权、撤销及品牌变更。公开地址迁移的生产执行记录由部署方补充。
- 工程与财务岗位共同复核五期净收款、退款、补收、交付后尾款、待补与实收、业主签收凭据。
- 管理层按 B01～B04 补齐节点完成标准、薪酬公式、付款计划审批权限和费用分摊规则。项目负责人按 B05～B06 确认清理范围与三家陪跑公司的岗位名单。

## 8. 节点 04～06 进度（2026-11-26）

| 节点 | 已核验 | 待办 |
|---|---|---|
| 04 发布链接与客资 | 小程序 75 项通过。后端发布 18 项、CRM 与线索导入 44 项通过。覆盖回执丢失、重复提交、链接 A/B、待关联及组织隔离 | 用实际业务岗位和真机复核弱网发布回填与客资归属 |
| 05 退役 PR | 已按补丁比较 #26、#27、#106、#107。手工工程合同备案与财务并发保护已有主线实现。旧模拟收银和支付模式迁移继续退役，逐项依据见执行日志 §10 | 真实支付渠道联调列入节点 12 |
| 06 我的作品 | 后端 #216、小程序 #218 已实现个人回收站、恢复和作者校验。保留已发布作品播放和客户来源。后端 36 项、小程序专项 49 项及全套检查通过 | 跟进双端 PR 和主线配对，真机核对旧作品与历史发布 |

## 9. 并行开发进度（2026-11-26）

| 节点 | 已交付及验证 | 接续工作 |
|---|---|---|
| 07 内容获客 | #213 / #214 按岗位展示内容、咨询、去重客户、有效签约及净实收客户。两条来源的同一客户只计一次。已与节点 06 的后端合并测试 | 业务方补充“成交”的正式事件和多触点业绩分配规则 |
| 08 设计与快速预算 | 按项目负责人确认修订原 #211 / #212，双方已合并。门店、灵感库、客户跟进和已有方案均可进入预算。独立及项目预算按现行计价和公司积分规则计算、保存、回读，快速预算与正式客户报价各有入口。后端 252 项、小程序专项 189 项通过 | 真机核对历史预算和导出 |
| 09 工程成本 | #215 修正最后一笔出库及实收数量更正。#219 / #217 在门店和总部展示应付余额，保留历史确认的金额。真实 PostgreSQL 和页面配对已通过 | 开发材料流转操作入口，补施工异常责任人和时限。公共费用分摊待 B04 |
| 10 客户到工程 | 八步跟进、外部委托、合同与开工的小程序专项 38 项通过 | 复验多种付款期数和退回重交，修正预约跟进日期及从待办打开客户的路径 |
| 11 绩效与运营 | 公告、培训、导航、公司额度的小程序专项 157 项通过。已复现课程撤权后页面保留上一次课件 | 修复培训页面并复验后端。人工薪酬核算、实际发放记录、正式薪酬公式分别核查 |
| 12 上线验收 | #208 合并版本两项主线 CI 通过，持续按 PR 核验配对 | 待冻结最终两端 SHA，并安排真机、实际岗位、三家公司及环境恢复验证 |
