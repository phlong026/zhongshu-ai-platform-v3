# R12 付款计划继承

## 业务草案

- `GET /franchise/sales/customers/{customerId}/payment-plan-draft` 无草案返回 revision=0，GET 不写库。
- `PUT` 同路径，传 `Idempotency-Key`，body `{revision,totalCents,stages,note}`；stages 使用 R11 的金额/比例校验，不能引用正式 stageId。销售、销售主管和老板按原客户权限读写。
- 每次保存追加不可变版本。草案没有 contract/stage/receipt 行，不进入应收和实收。
- `CustomerPaymentPlanDraftPort.snapshotForEntrust` 供 R09 在提交委托时冻结；已签正式合同优先，否则返回最新草案，无资料则 null。委托保存快照，不持有可变草案引用。

## 工程继承

- `GET /franchise/engineering/{id}/payment-plan` 只对项目负责人、执行工程师和本公司财务/老板提供正式付款计划、准备时版本、needsRefresh、contractChanged。
- `POST /franchise/engineering/{id}/prepare` 新增 paymentPlanVersion。已签合同存在时金额和合同附件均由服务端读取；只需提交 note/workers/nodes/version，不重填付款比例。
- 可保留原客户端金额字段，但如与正式计划不一致会拒绝，避免旧三期页面覆写新计划。NODE_COMPLETED 必须引用当前工程实际存在的 nodeIndex。
- 工程与合同加锁后保存当前 planVersion 和完整快照；工程 terms 仅为准备记录。正式应收和实收一直读取销售统一账。
- 已收款不妨碍继承由财务合法修改的新计划；已冻结合同作废/重签则拒绝自动改绑。存量无正式合同的手工约定保留原显式三期输入，不能从委托草案自动产生应收。
- 未开工但正式计划版本变化时，先重新确认开工准备。ON_SIGN / BEFORE_START 的每个节点均须达到净实收，退款会恢复欠款门槛，其他节点不能用超收抵消。

## 催收与日期

- 工程收款、余额和催收使用稳定 stage key。任意自定义节点、尾款均可选，不把未知节点默认记为首款。
- 指定日期使用 dueDate；NODE_COMPLETED 使用指定节点实际完成日；ON_FINISH 等所有节点实际完成；MANUAL 未约定日期时不显示虚构逾期。旧 BASE/CAP 保留历史含义，不猜造基础节点编号。
- 催收等待实际施工触发条件达成；实际到账仍可登记提前付款，原封顶款旧门禁兼容保留。
- 待办读取与工程余额同源的净实收，包含退款和新增/调整节点。

## 验证边界

新增草案 PG 集成用例、工程继承/版本/门槛 PG 用例和日期规则单测，纳入 Backend CI。工程测试用合同端口固定输入隔离施工服务；全链正式签约与接收的配对验证由 R22 使用确定前后端 SHA 运行。部署前须先应用 R11 migration 1050，再应用 1051/1052。回滚脚本存在业务证据即拒绝丢弃。
