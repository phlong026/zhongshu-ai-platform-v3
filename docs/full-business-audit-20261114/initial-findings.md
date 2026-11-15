# 初始断点与解决方案

日期：2026-11-14。证据层级：当前前端源码、独立 B 候选源码、开发者工具环境只读检查。本文件不把静态差异写成生产已复现问题，不将旧巡检问题整批重开。

## F01：设计委托的确认及数据字段契约不一致

- 影响：提交委托、设计完成/下发、资料移交和工程接收，是主链优先验证项。
- 前端入口：[委托页](../../miniprogram/pages/workflow/commission/index.js)的 `commandPayload()`、`submit()`；[API 封装](../../miniprogram/api/franchise/commission.js)直接传递请求体。
- 后端候选：`众墅之家AI赋能平台/.worktrees/b-independent-20261114/平台程序/domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/controller/admin/CommissionController.java`。本轮读取候选 HEAD 为 `ec586bca`；这不是当前 api2 运行产物证明。
- 已完成独立只读交叉审查，确认下表源码差异。后端路径下文均相对 `平台程序/domains/franchise/franchise-commission/src/main/java/cn/iocoder/yudao/module/franchise/commission/`；源码行号对应本轮基线。

| 断点 | 前端证据 | B 候选后端证据 | 业务影响与最小解决方向 |
|---|---|---|---|
| F01-a 详情结构 | `index.js:99` 将返回直接交给 `presentation.js:75`，按扁平 `request/fee/completed/dispatch/handover` 读取 | `controller/admin/vo/CommissionRespVos.java:90` 返回 `commission/submissions/progress/handovers/feeApproval/allowedActions/displayStatus` | 已有委托可能显示成未提交或错误阶段。根据正式 DTO 显式映射当前提交、进度事件及有效移交，保留历史，不用猜别名掩盖缺字段 |
| F01-b 提交与计价 | `index.js:187` 提交 `checked`、分单位 `totalCents/firstCents`、附件对象；`:238` 总部计价发送 `rateCents` | `controller/admin/CommissionController.java:59` 使用 `confirmed`、元单位 `total/first`、字符串附件；`DesignPolicyController.java:38` 当前固定费用保存只允许门店老板 | 当前新标准计价页面与后端固定首尾款不是一个契约。先冻结标准单和历史单的有效规则；标准面积计价应由服务端按有效预算/价格版本算价，历史模式显式转换金额与确认字段，分别验证 |
| F01-c 完成与下发 | `index.js:209` 等发送 `checked:true`；完成说明可为空 | `controller/admin/CommissionController.java:128` 读取 `confirmed`；`CommissionService.java:464` 要求确认及非空说明 | 当前页面 payload 无法满足该候选的办理门禁。统一确认字段与完成说明必填规则，补真实页面 payload 的命令测试，保留服务端校验 |
| F01-d 移交包 | `index.js:217` 使用 `dispatchVersion`，不传 `managerUserId`，文件为对象 | `controller/admin/CommissionController.java:156` 读取 `dispatchSeq/managerUserId` 及字符串文件；`HandoverService.java:224` 校验下发版本和工程任职 | 版本/负责人解析为 0 或文件引用不匹配可阻断移交。明确正式下发序号与持久资产引用；按已确认“本组织默认工程负责人”规则由服务端解析并验证接收人，不能为匹配旧 DTO 擅自给前端增加个人选择流程 |
| F01-e 退回与接收 | `index.js:223` 等退回发送 `note`，接收发送 `checked` | `controller/admin/CommissionController.java:134` 等读取 `reason/confirmed`；`HandoverService.java:155` 校验 | 退回原因或接收确认丢失，原单无法接续。统一 DTO；覆盖退回→补件重交→接收→唯一工程建档的连续用例 |
- 最短验证：用隔离环境真实岗位，从委托页勾选并提交；保留完整脱敏请求、业务响应、确认字段解析结果和状态前后。先做契约测试验证同一实际页面 payload 在目标 DTO/命令中如何解释。
- 修复方向：在前后端边界明确规范 DTO，保持既有业务步骤与服务端门禁；校准确认字段、金额单位、附件引用、正式版本和移交参数。增加从页面实际 payload 到目标 Controller/Service 的行为测试，并验证同单重试及下游待办。不能简单删除确认校验来“跑通”。文件必须完成 B 资产上传与授权校验，不能把临时本机路径当跨人可用成果。
- 当前状态：源码差异已发现；真实 API 复现、完整状态及账目验证待执行。

## F02：当前工具导入目录与 B 运行版本尚未形成同一测试基线

- 证据：开发者工具实际导入主 checkout 的 `miniprogram`；本方案在另一 worktree。storage 没有 API base 覆写，当前源码默认 api2。B 准备材料并未证明新 B 服务已运行。
- 影响：可能测到旧服务，或误把其他工作树代码当作本次页面行为，造成错误归因。
- 解决方案：每轮固定前端/后端/Web/Worker 版本、实际导入目录、API base、运行制品及环境；用 B 专用隔离配置和测试数据执行。先做一个带已知业务 ID 的读写回读冒烟，再开始完整流程。A 保持原有边界。
- 当前状态：测试前置条件，未认定为生产故障。

## F03：历史支付说明、展示商城与正式交易范围需分开

- 证据：历史面积计价文档描述一次性体验模拟支付；当前委托页注明已下线模拟支付。账号页部分服务订单、采购路由转换为展示；B 部署清单明确首发充值关闭。
- 影响：照旧清单可能测试无效入口，或把展示页当作已开放商城成交、将模拟支付当正式实收。
- 解决方案：以当前前端可达动作逐项列出“展示/咨询、正式交易、关闭、待建设、旧 A 能力”，分别形成验收范围和缺口表。已有交易按订单→付款通知→权益/履约→退款→对账测试；未开放业务保留状态，不擅自启用。
- 当前状态：范围与历史资料冲突待冻结，非已证明的订单缺陷。
