# R21 迁移清单、测试公司关闭与恢复演练

## 现有能力复用

沿用 OrgDirectoryService.lifecycle 的正式关闭/启用流程、组织锁、资金在途检查、权限、钱包会话撤权和 pf_org_admin_audit。工具没有数据库写入通道，不新建客户、工程、财务或个人钱包账，不物理删除任何资料。所谓测试清理是将指定测试门店退出有效工作范围，历史账本和证据继续保存；恢复重新启用组织，旧已撤销会话不会重新变成有效会话。

数据库只读 inventory.sql 用于旧岗位、委托、待批、缺失合同证据、实际开工记录、公司池与收付款总量盘点。必须指定 tenant 和精确 company IDs；requested 与 matched 必须一致。输出只包含业务编号和状态，没有客户姓名、电话。此盘点不能替代资产验收或八步服务的完成判定，不会补造任何已完成节点。

## 在途映射

| 旧记录 | 处理 |
|---|---|
| FEE_PENDING/FEE_RETURNED/DESIGNING/DESIGN_COMPLETED/DISPATCHED/HANDOVER_RETURNED | 在 R09 按真实资料、当前revision和原因逐单转换；待批审批由业务服务记为SUPERSEDED，原费用留存 |
| HANDOVER_SUBMITTED | 先在原移交流程接收或退回，再决定是否转换，不能批量强制改状态 |
| ACCEPTED / 已建工程 | 保留原工程和版本，不重复接收建工程 |
| 已签但日期或附件缺失 | R08 补证命令办理；不从历史文字或推算日期自动标完成 |
| 已开工但现场依据不足 | 工程实际记录核对；R05/R10按真实事实计算第08步 |
| 旧设计行政、设计师或负责人任职 | 只盘点不批量替换；R09/R10通过、在途依赖清零后，按实名名单用既有成员任职撤销功能办理 |

撤销旧岗位前需复核 inventory 中旧在途和待批均已逐项处理。此任务不会把旧设计行政账号静默改为有全品牌查看权的总部管理员。

## 存量公司零额补池

新增 POST /admin-api/platform/org-points/{orgId}/provision，body 为 reason/requestKey。沿用平台超管权限，在事务内复核有效用户、角色、目标真实 tenant 与 store/headquarters。重复和并发只补唯一公司池，不重置余额、冻结状态或赠额，不迁移个人历史积分。org_point_pool_backfill 保存只追加审计及幂等请求；新公司继续走 R19 注册事务。

此接口是维护工具，不给普通总部财务扩大写权限。读权限、实体费用登记和微信充值流程不变。

## 明确名单与操作命令

目标文件必须由业务确认，禁止按名称包含“测试”推断名单。格式示例仅展示结构，下面编号是占位，不对应任何已批准真实公司：

```json
{"tenantId":"1","reason":"经确认的陪跑测试数据整理","companies":[{"companyId":"123","confirmedName":"已确认的测试公司全名","action":"close"}]}
```

action 只允许 close 或 provision。关闭仅允许门店，拒绝总部和未结算生成预占。`ZSH_MAINTENANCE_TOKEN` 由现有授权操作员在自己的运行环境提供，不写入参数、文件或日志。base-url 仅接受 HTTPS origin；认证请求拒绝跳转到其他地址。

```bash
python3 平台程序/script/business-flow-migration/rehearsal.py plan --base-url https://approved.example --manifest approved-companies.json --journal reviewed-batch.json
python3 平台程序/script/business-flow-migration/rehearsal.py apply --base-url https://approved.example --journal reviewed-batch.json
python3 平台程序/script/business-flow-migration/rehearsal.py restore --base-url https://approved.example --journal reviewed-batch.json
```

plan 只读，保存公司编号/名称、状态、额度、流水数量、最近审计、精确请求体和稳定幂等键；不保存联系人和令牌。先审查这个具体计划，再执行 apply。每家公司提交前将状态写为 SUBMITTING，先 fsync 文件，替换后再 fsync 父目录；响应丢失时以原请求体和同一个键续跑，不能再生成一个新批次。

工具逐家公司执行，跨公司不是一个数据库事务。中断后原日志明确哪些公司完成；既有接口确保单公司的关闭、审计和会话撤权原子一致。恢复使用 reopen，不恢复旧会话、不改账，不删除已补建的零额公司池。恢复前既检查最后操作仍属于本批次，也把 expectedAuditId 交给后端在组织锁下复核，避免覆盖后来的管理决定。原客户端未传此字段时保留旧调用兼容。

## 验证与运行边界

11项工具用例涵盖只读计划、明确ID、禁止删除/赠额、快照变化、响应丢失同键重试、恢复中断、后续人工变更保护和历史保存；本地全部通过。新增真实 PG 用例覆盖补池权限、跨租户、停用组织、零额/余额及冻结状态保持、审计幂等与回滚、生命周期版本复核，纳入 Backend CI。本地无 Maven/PG，数据库运行结果以CI为准。

本次未收到测试公司实名编号、测试环境地址或操作员凭证；没有执行真实关闭、恢复、补池、岗位撤销或数据删除。inventory 及 apply/restore 应先在隔离的陪跑候选环境演练，记录 R22 固定前后端版本。
