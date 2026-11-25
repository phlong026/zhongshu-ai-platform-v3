# R15 薪酬规则与人工核算页面

## 入口与范围

新增管理分包页面 `/pages/admin/payroll/index`。现有门店财务工作台增加“薪酬核算”；老板的经营页面增加“薪酬核算记录”。页面分业务岗位 `BUSINESS`、工程岗位 `ENGINEERING`，按月份查询核算记录和规则版本。

门店 `finance` / `finance-lead` 可以登记管理层提供的规则，并录入本公司有效岗位员工的人工核算。`boss` / `owner` 只读。页面绑定登录用户、租户、当前任职和组织；后端继续核对真实任职及公司，客户端不扩大权限。

金额只支持财务逐项填写的底薪、提成和其他调整。没有管理层规则时明确显示“薪酬规则待配置”，没有自动工资公式或默认底薪。人工核算记录用于对账，不等于发放工资，也不会发起真实资金支付。已有费用登记与付款流程保留。

## 规则与核算版本

- 规则记录生效月份、底薪约定、考核要求、提成约定、管理层确认依据，必须再次勾选确认；修改产生新版本。
- 选择员工时调用专用 `/latest` 确认该员工、岗位族、月份的真实核算版本，不从列表第一页推测版本为零。
- 更正核算会载入已有人工金额，但清空本次说明与确认勾选。保存使用 `expectedVersion`，保留原历史金额和原规则快照。
- 提成明细可关联本公司真实工程和动态付款节点，显示后端返回的节点净回款作为人工核对依据，不推算提成比例或发放资格。
- 记录详情展示该版本实际引用的规则和核算当时的回款证据；历史按员工、月份与岗位族分页。

普通接口前缀为 `/franchise/finance/payroll`，页面使用 `/context`、`/rules`、`/records`、`/latest`、`/records/{id}/versions`、`/projects/{id}/stages`。仅规则和核算记录使用 POST；具体结构见后端 R15 `payroll-ports-contract.md`。

## 总部只读复用

总部工作台可导航至 `/pages/admin/payroll/index?headquarters=1&companyId={公司ID}`。总部入口由 R22 接入；本任务提供页面模式及行为验收。

页面首先读取 `/platform/headquarters/context`，确认 `readOnly=true`、当前 `tenantId`、`headquartersOrgId`、`postId`、`roleCode`，并确认目标公司仍在 `companies` 内。仅总部 `hq-admin`、`finance`、`finance-lead` 可进入。

之后只使用 `/platform/headquarters/companies/{companyId}/payroll` 的只读端点：

| 页面内容 | GET 路径 |
| --- | --- |
| 规则版本 | `/rules?family&pageNo&pageSize` |
| 月度核算记录 | `/records?month&family&pageNo&pageSize` |
| 员工当月历史版本 | `/records/{id}/history?pageNo&pageSize` |

该模式不加载普通门店编辑上下文或员工候选，不显示录入按钮，所有写处理器拒绝执行。列表已包含完整不可变核算快照，可直接查看详情。HQ Controller 通过 R15 `PayrollReadPort` 和 R02 总部授权读取，不伪造门店任职。

## 重试与异步一致性

保存前冻结命令内容和幂等键。结果不确定时锁定表单，保留“用原请求重试”；离页清除工资、姓名和表单展示，返回后重新检查当前任职和公司，再允许原键重试。切换身份、租户或公司会丢弃旧请求；明确版本冲突要求重新加载，禁止继续覆盖。

列表、员工版本、付款节点、历史页都有独立请求标识。刷新会废弃旧分页；刷新当前上下文期间不会启动旧的下一页。服务端拒绝权限时清空核算和历史；迟到响应不能恢复已离开的工资内容。长 ID 全程使用字符串，金额以分精确处理。

## 验证结果

新增 `scripts/verify-payroll-ports.cjs` 的 17 项真实 Page/WXML 行为检查已通过，并加入 `npm run check`。覆盖人工金额及确认、规则版本、专用 latest、动态节点、原键重试、切岗及撤权、分页竞争、总部只读接口和已有财务/老板入口。

完整 `npm run check` 和 `npm run check:packages` 已实际通过，代码与样式均放在管理分包，主包继续保留既定 128 KiB 预算。验证日志分别为 `/tmp/r15-mini-full-check.log` 与 `/tmp/r15-mini-package.log`。本机检查不替代真机联调及固定 SHA 的后端 PG CI。
