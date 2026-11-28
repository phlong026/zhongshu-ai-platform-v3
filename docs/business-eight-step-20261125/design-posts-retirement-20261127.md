# 设计岗位退役节奏（2026-11-27 口径）

> 本文件保留退役分期计划及当批状态。后续三岗目录/模板退役和新 LEGACY 封口已分别由 #314/#315、#342/#343 落地；恢复与环境证据仍按[统一整改总表 BIZ-T12/T13](../roadmap/V3-business-engineering-remediation-20261127.md#biz-t12)跟踪。下文“逐单转换或原流程推进完毕”不是对所有旧单的执行授权：仅符合状态及历史结算前置的原单可转换，`HANDOVER_SUBMITTED`/`ACCEPTED` 不支持直接转换；移交中单保留受限原单承接，已接收单保留原工程。旧委托费用审批/成果/下发新增动作已收口，不能按历史计划重新开放；真实收付退款、附件、审计和必要历史结算保留。

业务决定：**设计岗位（hq-designer 设计师、hq-design-lead 设计负责人、hq-design-admin 设计行政）全部取消**。设计由外部软件线下完成，系统内只保留：设计文件上传（八步 05 步设计合同附件、06 步设计文件附件）与移交工程（外部委托承载的移交包）。本文件记录三阶段落地节奏与本批已完成项。

## 阶段①：前端与服务端入口收口（本批，已完成）

- 首页快捷「提交委托」改名「外部委托」，落点外部委托登记页（选客户直达）；旧 `intent=commission` 深链兼容。
- 下线「已存参考预算，待提交设计委托」待办（后端 `FranchiseTodoProvider` 不再生成，前端标签映射删除；配套后端 PR 与本 PR 同批）。
- 设计行政（hq-design-admin）不再直达旧委托工作台（`view=admin`/`view=projects` 深链删除），落通用业务工作台视图；学习目录章节改「外部委托与合同办理」。
- 八步 05/06/08 步的旁挂入口继续可用（外部委托登记 → 工程移交卡 → 移交页），移交包仍由外部委托实体承载。

## 阶段②：存量处置（随 B05/B06 迁移批）

- 存量设计行政账号：转岗（改配业务/管理岗）或停用；停用前核对其在途待办（旧委托费用审批/设计完成/下发）。
- 在途 LEGACY 委托：逐单 `convert-legacy` 转外部流程（保留编号与历史）或按原流程推进完毕；转换入口本轮保留。
- 迁移清单中设计域表（旧委托/费用/下发清单）按「只读保留」登记，不删除。

## 阶段③：服务端与模板退役（最终批，依赖①②完成）

- 岗位模板与角色/权限种子删除迁移：`pf_org_type`/`pf_post_template` 中 design 条线三岗、`franchise-system` 中对应 `system_role`/`system_role_menu`（菜单 810108「提交设计委托与移交」等设计行政权限）、`franchise:design:use` 权限矩阵收敛。
- `organization.js` 残留清理：`templates.headquarters` 里的 `hq-design-admin`、`enabled()` 白名单、`canBusiness` 列表、`teamForRole` 的 design 分支。
- LEGACY 服务端退役：`CommissionService.submit` 等旧接口、旧委托工作台页面（`pages/workflow/commission` 的 submit/admin/projects 视图）、`external-entry.js` 转发层、`utils/sales-flow.js` 旧本地模型。
- `CustomerService.listWritableWithBudget` 与 `CustomerSchemeMapper.selectCustomerIdsWithBudget`（预算催办已退役，仅测试使用）随批删除。
