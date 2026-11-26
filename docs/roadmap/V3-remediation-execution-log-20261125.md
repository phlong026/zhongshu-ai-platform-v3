# 众墅之家 V3 V1.1 整改执行日志

> 执行依据：`docs/roadmap/V3-module-remediation-plan-20261125.md`
> 执行仓库：`phlong026/zhongshu-ai-platform-v3`
> 建立日期：2026-11-25（北京时间）
> 当前进度与[整改任务总表](V3-module-remediation-plan-20261125.md)同步，本文件后半部分保留历次执行和验证证据。

## 全部整改任务的当前状态

截至 2026-11-26 13:59（北京时间），已核对小程序主线 `4ab766ca1b7b767c57920f044b57c3bf303a1596` 和后端主线 `323e6106a61ef52bf8b45dd595025e43fa1e9df6`。整改总表与执行日志在同一提交更新本节。后文的阶段记录保留当时的检查结果。

### 13 个执行节点

| 节点 | 任务 | 当前状态 | 已验证及待办 | 对应 PR |
|---|---|---|---|---|
| 00 | 基线与任务冻结 | 已完成 | 两条主线、历史差异、复用评估和执行规则已登记。#175 | [#175](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/175) |
| 01 | 财务更正续办 | 已合并，待财务岗位验收 | 更正后丢失回执可续办，专项接入标准 CI。#171、#195 | [#171](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/171)、[#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) |
| 02 | 门店公开地址与总部权限 | 已合并，待部署和账号验收 | 独立公开地址、同品牌过滤及更换地址重新授权。#196、#201 | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196)、[#201](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/201) |
| 03 | 工程收款、到货与交付 | 主要专项已合并，授权跟进评审中 | 收款 #199/#202/#209，材料 #204/#206/#207，交付结算 #208。#210 跟进加锁前的负责人校验。 | [#199](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/199)、[#202](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/202)、[#204](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/204)、[#206](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/206)、[#207](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/207)、[#208](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/208)、[#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209)、[#210](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/210) |
| 04 | 发布与客资 | 自动化回归通过，待真机 | 小程序 75 项，后端发布 18 项，CRM 和客资 44 项通过。 | [#164](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/164)、[#168](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/168)、[#155](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/155)、[#84](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/84) |
| 05 | 历史 PR 差异 | 差异核查已完成，真实渠道另验 | 逐项对照 #26、#27、#106、#107，核对主线替代接口、并发保证与退役流程。 | [#26](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/26)、[#27](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/27)、[#106](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/106)、[#107](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/107) |
| 06 | 我的作品 | 双端已合并，待原岗真机验收 | 个人回收、恢复、原稿和历史发布来源。后端 36 项、小程序 49 项通过。 | [#216](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/216)、[#218](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/218) |
| 07 | 内容获客统计 | 后端已合并，小程序评审中 | 真实响应送入小程序验证签约、净实收和多来源客户去重。后端 56 项、小程序 44 项通过。 | [#213](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/213)、[#214](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/214) |
| 08 | 设计与快速预算 | 已合并，待门店真机验收 | 保留独立与项目快速预算、计价、扣点、保存、历史及导出。平面与立面 AI 生成已退出。后端 252 项、小程序专项 189 项及两端 CI 通过。 | [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211)、[#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) |
| 09 | 工程成本与项目账本 | 项目核算双端已合并，材料双端评审中 | 材料数量保护复验 74 项。项目核算后端财务域 126 项、小程序 108 项。材料去向后端 75 项，小程序 343 项，施工整改继续开发。 材料页面 #224 的前端 CI 失败，正在查因。 | [#215](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/215)、[#219](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/219)、[#217](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/217)、[#225](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/225)、[#224](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/224) |
| 10 | 客户、委托、合同至开工 | 主链配对通过，待办修复进入 PR | 隔离基线 74 项通过。两期、三期、五期均已从签约走到退回、重交、接收及开工；跟进待办 44 项后端及 39 项小程序通过。 | [#221](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/221)、[#222](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/222) |
| 11 | 绩效、公告、培训与公司额度 | 部分 PR 评审中，发放登记核查中 | 六组后端 84 项及小程序 157 项通过；培训撤权专项 47 项，并验证真实 403/404 后清空课件。 | [#157](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/157)、[#160](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/160)、[#220](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/220) |
| 12 | 迁移、配对与上线 | 隔离演练进行中，正式上线待验 | Linux 离线回归 55 项通过。完整源码清单仍发现历史基线后新增的迁移，详见 QA-01。真机及三家公司尚待安排。 | [#149](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/149)、[#161](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/161)、[#163](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/163)、[#169](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/169)、[#223](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/223) |

### 子任务与待补资料

| 任务编号 | 节点 | 当前状态 | 交付证据或尚待补齐的事项 |
|---|---|---|---|
| BASE-01、BASE-02、BASE-03、BASE-04、BASE-05、BASE-06 | 00 | 已完成 | 基线、PR、代码与数据库、失败线索、开源复用和执行日志均已登记，#175 已合并。 |
| AUTH-01 | 02 | 已合并，待总部岗位验收 | 总部跨品牌只读已落地。用真实总部管理员及财务账号核验。 |
| AUTH-02 | 02 | 已合并，待门店验收 | 同品牌安全地图与公开门店投影已落地。核对两品牌实际门店。 |
| AUTH-03、AUTH-04 | 02 | 已合并，待跨范围业务回归 | 列表、详情、搜索、附件、导出和多租户边界按岗位核对，保留总部与加盟商区别。 |
| AUTH-05 | 02、11 | 已合并，撤权按页面继续回归 | 会话失效、切岗及旧页面需真机核对。培训页面另在 OPS-04 修复。 |
| AUTH-06 | 02 | 已合并，待迁移和现场验收 | #196 的公开地址和 #201 的换址重新授权已合并。#200 改善组织开户测试夹具。 |
| CRM-01 | 10 | 待 B01 | 八节点的必填材料、跳步、回退和完成条件待管理层签定，保留现有服务端进度。 |
| CRM-02、CRM-03 | 10 | 代码已合并，待岗位复核 | 自动化已核对签约和实际开工条件及列表/详情进度；用真实客户复核更正与回退。 |
| CRM-04 | 10 | 代码已合并，两端配对通过 | 验证客户、外部成果、合同、交接及工程使用同一项目和版本。 |
| CRM-05 | 10、12 | 历史路径已回归，待历史样本 | 六类在途委托的原号、费用及版本可追溯，实际旧客户证据映射待环境验收。 |
| CRM-06 | 10 | 双端 PR 评审中 | #221/#222 按上海业务日生成提醒，打开对应客户跟进页，验证转交和去重。 已用同一客户验证原负责人、调岗后负责人和办结的待办。 |
| CONTENT-01、CONTENT-02 | 04 | 已合并，自动化通过 | 发布回填、实际链接 A/B 核对、重复操作及回执丢失已回归，待手机弱网。 |
| CONTENT-03、CONTENT-04 | 04 | 已合并，待业务验收 | 咨询来源、待关联及任务标题分别接续 #155、#84。旧作品与历史发布见节点 06。 |
| CONTENT-06、CONTENT-06A | 06 | 双端已合并，待业务验收 | #216/#218 接入权威作品编号、版本与可管理标记，兼容旧文本作品。 |
| CONTENT-06B、CONTENT-06C | 06 | 双端已合并，待业务验收 | 验证作者权限、回收及恢复、原任务结果、已有发布可播放并保留客户来源。 |
| CONTENT-05、CONTENT-07 | 07 | 后端已合并，小程序评审中 | #213/#214 按客户去重，分别统计有效签约及净实收。成交事件与多触点业绩分配待业务确认。 |
| DESIGN-01 | 08 | 已合并，待资料抽验 | 保留户型检索、资料详情、预览及从灵感库发起快速预算。 |
| DESIGN-02 | 08 | 已合并 | AI 平面、立面出图和候选确认已退出，门店可在客户流程上传外部成果。 |
| DESIGN-03 | 08 | 已合并，待真机复核 | 快速预算保留，支持门店、客户、灵感库和旧方案入口，独立及项目预算可生成、重试、保存和回看。正式客户报价另有入口。 |
| DESIGN-04、DESIGN-05 | 08 | 已合并，待真机复核 | 保留历史图纸和预算结果，旧链接校验角色、项目和版本，核对相册下载和导出。 |
| ENTRUST-01、ENTRUST-02 | 10 | 已合并，端到端配对通过 | 外部成果的客户归属、版本冻结、退回重交及双方可见历史已回归。 |
| ENTRUST-03、ENTRUST-04 | 10 | 已合并，端到端配对通过 | 核对外部报价、签约和移交，可在没有内部预算或设计行政账号时办理。 |
| ENTRUST-05 | 10 | 已合并，端到端配对通过 | 指定工程岗接收、退回及重交，多次办理仍只建一个工程。 |
| ENTRUST-06 | 10、12 | 代码与隔离测试通过，待历史样本 | 保留在途委托原号、原版本与已进入交接的记录。历史岗位待办及生产迁移待核。 |
| ENG-01 | 03、10 | 已合并，配对通过 | 两期、三期、五期签约到工程开工均已验证。付款计划变更权限待 B03。 |
| ENG-02、ENG-03 | 03 | 已合并，待工程财务合验 | 按各期净收款核验，退款后重新拦截，已约定的交付后尾款单独处理。 |
| ENG-IDEM-01 | 03 | 已合并 | 收款的日期、凭证、项目和相同请求并发重放已由 #199、#202、#209 接续。#203 作为重复工作关闭。 |
| ENG-IDEM-02、ENG-IDEM-03 | 03 | 已合并 | #204 保留计划批次与原行，#206 复核到货、补发、质检及旧应到量。历史原单无法唯一定位的需业务核对。 |
| ENG-IDEM-04、ENG-IDEM-05 | 03 | 主体已合并，授权跟进评审中 | #207 核对更正及材料流转，#208 处理交付/结算并发与凭证。#210 补充负责人校验，三组工程回归 56 项通过。 |
| ENG-04 | 03、09 | 部分已合并，数量和流转双端评审中 | #215 保护末批数量及已领用材料。#225/#224 支持领用、损耗、退料、调拨、补发签收及质检换货，后端 75 项、小程序 343 项通过。 |
| ENG-05 | 09 | 财务域回归通过，待岗位验收 | 劳务、材料、差旅等按原单归集；项目净现金、应付余额与毛利分别核对。 |
| ENG-06 | 09 | 已定位缺口，开发中 | 已有整改状态和事件。补负责人、截止日、处理历史及相应岗位待办，旧单保留原始事实。 |
| ENG-07 | 03、10 | 已合并，待实际岗位验收 | #153/#154 的负责人交接保留。核对调岗、离岗及历史人员记录。 |
| FIN-01、FIN-02 | 03、09 | 基础已合并，隔离账本已回归 | 分期、部分收付、原凭证及退款净额已核对，真实资金单据待财务复验。 |
| FIN-03、FIN-03A、FIN-03A-CI-01 | 01、09 | 已合并，待财务岗位验收 | 退款/支出退回、作废替代、丢失回执续办均按原记录办理。#171/#195 已入正式 CI。 |
| FIN-04、FIN-05 | 09 | 基础已合并，财务域回归通过 | 核对前期设计费用归属、劳务及材料来源，项目账本保留原始单据。 |
| FIN-06、FIN-07 | 09 | 项目核算双端已合并，待财务实单 | #219/#217 增加当前待付和确认快照。实际用例为当前 85 元、原确认 70 元，旧快照缺字段显示“—”。 |
| FIN-08 | 05、09 | 并发回归通过，待财务岗位验收 | 已复验员工垫付、报销、原单退款及结清的并发处理，旧 #27 的差异已核查。 |
| PERF-01、PERF-02 | 11 | 人工规则已合并，正式公式待 B02 | 业务及工程按有效月份和规则版本保存，由管理层给出底薪、提成基数和冲回条件。 |
| PERF-03、PERF-05 | 11 | 已有人工核算，待业务验收 | 绩效看板 7 项、薪酬 11 项通过。多人归属、员工申诉、人工归档需岗位确认。 |
| PERF-04 | 11 | 待 B02 | 正式公式、生效版本与退款处理确定后再实现自动核算。 |
| PERF-06 | 11 | 原付款链路核查与开发中 | 财务已有线下付款登记，人工核算不是发放凭据。补员工/月度应发、实发和未发的可靠关联。 |
| OPS-01、OPS-02、OPS-03 | 11 | 已合并，自动化回归通过 | 公告首页及个人入口、置顶、下架和品牌授权已回归。后端公告 11 项通过。 |
| OPS-04 | 11 | 小程序 PR 评审中 | #220 修复撤权或下架后继续显示旧课件。小程序 47 项、后端 27 项通过，真实接口 403/404 与页面配对通过。 |
| OPS-05、OPS-06 | 11 | 已合并，待多岗真机验收 | 已回归岗位首页、公告和个人导航。旧通知、返回栈和多岗切换待手机实测。 |
| WALLET-01、WALLET-03 | 11 | 隔离回归通过，真实渠道待验 | 开户、查单和重复回调沿用公司账本。钱包及建池迁移 28 项全部执行，包含原生页面到 Spring 的本地 HTTP 配对。 |
| WALLET-02、WALLET-04、WALLET-05 | 11、12 | 已有实现，待渠道和业务对账 | 真实微信充值、渠道退款、视频失败/取消和公司对账仍需持有渠道权限的岗位验证。 |
| WALLET-06 | 05 | 历史差异核查已完成 | #106/#107 关闭，当前公司额度沿用 #142/#143，旧模拟收银不进入正式渠道。 |
| QA-01 | 12 | 静态与隔离回归通过，完整清单仍有差异 | #223 修复 13 类写入口静态校验及跨平台路径。对照后端 4e5acd3c，现有清单 70 项，实际源码 147 项，相差 77 项须逐项核对。 |
| QA-02 | 12 | 待 B05 | 待项目负责人提供清理与保留名单和恢复范围。 |
| QA-03 | 12 | 隔离回归通过，实际环境待演练 | Linux 迁移及恢复日志 11 项通过。用目标环境备份验证恢复时点和数据范围。 |
| QA-04 | 12 | 各 PR 固定配对，待最终发布组合 | 每对提交以本页 SHA 表为准。合并后需用最终两条主线再冻结一次。 |
| QA-05、QA-06 | 12 | 待真机与 B06 | 需公网 HTTPS 后端及文件接口、微信真机、三家门店账号和岗位。 |
| QA-07 | 12 | CI 演练通过，目标环境待验 | 冻结及配对选择 21 项通过，实际 Flyway 升级、备份恢复与回滚另验。 |
| QA-08 | 12 | 已合并，持续回归 | #169 建立主包预算，节点 08 精确版本预留 131,313 字节。其他小程序 PR 逐一复验。 |
| B01、B02、B03、B04 | 01、09、10、11 | 待管理层及财务补齐 | 八步业务标准、工资公式、付款计划权限及公共费用分摊分别记录。 |
| B05、B06 | 12 | 待项目负责人补齐 | 测试数据清理和保留名单、恢复目标、三家公司、岗位与验收账号。 |

### 本轮代码与配对验收

| 节点及功能 | 对应 PR | 后端提交 | 小程序提交 | 同一版本的验收与合并记录 |
|---|---|---|---|---|
| 01 财务更正 CI | [#195](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/195) | `196944dd5e2e49644e244e794e08683d95eaa154` | `41136df0162ea221ff2ba12b4f61c4da91a78c3e` | 专项与配对通过，两端为当时核验主线。 |
| 02 公开地址 | [#196](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/196) | `b4ffd53e6fe6d18724a6d59b68cacaf7d60dbb58` | `a9eff5e8a402e0728f4466a6cdb2d42e9b9eb72f` | 合并至 `11014a5cf936166b71134bf40f838ad93b04ce43`，换址另见 #201。 |
| 03 并发收款 | [#209](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/209) | `af5c7eed6b71bda10c762bc03293154aab5ec569` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | 后端 CI 限流重跑通过，配对通过。合并至 `4c6419632dc36cc1a294b3f8d3a19068845ca70a`。 |
| 03 交付授权 | [#210](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/210) | `e8bc1f84aebd0d3dedeab66b8a89101eba835ed7` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | 项目、负责人和组织在加锁时复核，旧指纹及现用凭证分别比对。 56 项通过的专项提交为 `20288b66ebaf6168499fec360b0bb1f9e22fd29b`，最近分支更新后待 CI。 |
| 06 个人作品 | [#216](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/216)、[#218](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/218) | `30c07c558e025955832106a9a9ec6a1af804a3d9` | `f6625ecc23d2d317261b362b44f9b5b6070f89a3` | 真实数据库验证回收后旧发布可回放，新发布会被拒绝。 后端合并至 `3c171c40a8256fd5307d3b47408a2d4d16f46652`。 小程序合并至 `4ab766ca1b7b767c57920f044b57c3bf303a1596`。 |
| 07 内容统计 | [#213](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/213)、[#214](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/214) | `715431ed719ddaab2eb156b2a8faa70bc25ccac2` | `b1a9f27531442678b40719e3977d69c68a1bfed5` | 真实页面显示 2 条发布、6 次咨询、3 名去重客户、1 名签约、0 名净实收。与节点 06 联合编译，4 项交叉回归通过。 后端合并至 `323e6106a61ef52bf8b45dd595025e43fa1e9df6`。 |
| 08 快速预算 | [#211](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/211)、[#212](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/212) | `90625c8c03e4578cc3ba203fef302a79b99cb2c7` | `8377058cdfa98ad1c8501144556da63ab08523c2` | 后端合并 `f5be78e6e93dc173f4a238fd167dcde7f286ebc3`，小程序合并 `b5a5bd237f2358988ca648777047eb26dec1c57d`。 |
| 09 材料数量 | [#215](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/215) | `d2d74cc4c69c4f5facb6b41a68d5c09abb5aec7e` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | 已接续 #206 合并版本。74 项工程回归通过。 |
| 09 项目待付 | [#219](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/219)、[#217](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/217) | `921f72ff1e4545577a8e464d9107862473ba82a7` | `424915543ebc24c1063809139c2f1dd3f3767d43` | 劳务应付 100 元，支付 30 元后确认，再退回 15 元。当前 85 元和已确认 70 元同时可查。 两端合并至 `56ffe131109902935a68d22b71034c21fef63731` 和 `e877e5198c193534def650dcba3e57df1c949c05`。 |
| 09 材料流转 | [#225](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/225)、[#224](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/224) | `88ff3437289e57ca89d5fbc51e058e4bf1eef817` | `cc8b1a188c94ce3f4cd91fc81c3143b07d5f316f` | 真实页面读取实收 3、领用 0.5、退料待结算 0.25、可分配 2.25、待补发 1，主单和应付各保持原始来源。 #224 前端 CI 待修复。 |
| 10 客户待办 | [#221](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/221)、[#222](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/222) | `eb9896dda690273a683c7bbaccd7c8da5b2a944f` | `9840b81ed73411ace359f9d65dac7b199d889343` | 按上海业务日到期。点击直达对应客户的跟进记录，支持冷启动。 |
| 11 学习中心 | [#220](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/220) | `4e5acd3c83936843c2f05f9b555b51873e6a0e22` | `ae41b60b51cd8f4501b8fff52c8f4d4cc97c73a6` | 用同一课程的真实响应验证可见、撤权及下架。 |
| 12 离线清单 | [#223](https://github.com/phlong026/zhongshu-ai-platform-v3/pull/223) | `fbab40bbfd66da85810f6a02b53a01234202fa61` | `4ed9caf4029f033ba7ea1fb96cff89d9b28c4c8e` | Windows 所有权 23 项、Linux 三组 55 项通过，完整源码清单差异单列 QA-01。 |

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
