# 众墅之家 AI 赋能平台 · 微信小程序

本仓库采用两个长期分支：**`main` 保存原生微信小程序，`backend-main` 保存 Java 平台服务和 Vue 管理台**。小程序通过平台 API 接入账号、客户八步跟进、内容创作、灵感库与免费快速预算、外部委托、工程、财务和绩效。正式 B 平台入口在后端分支；历史 Node 联调服务及其报告只代表当时版本。

## 从哪里开始

| 目标 | 入口 |
| --- | --- |
| 小程序开发 | 当前 `main` 的 `miniprogram/` 与 `project.config.json` |
| 后端、总部管理台 | [backend-main](https://github.com/phlong026/zhongshu-ai-platform-v3/tree/backend-main) 的 `平台程序/` |
| 接口和会话约定 | [数据访问层说明](miniprogram/api/README.md)、[http.js](miniprogram/api/http.js) |
| 默认自动检查 | [package.json](package.json)、[Frontend CI](.github/workflows/frontend-ci.yml) |
| 业务与工程本轮整改 | [问题现状、最小整改方案、完成标准及验收清单](docs/roadmap/V3-business-engineering-remediation-20261127.md) |
| 部署和配对验收 | 后端分支的 [B 部署入口](https://github.com/phlong026/zhongshu-ai-platform-v3/tree/backend-main/平台程序/deploy/platform) 与 `平台程序/script/release-rehearsal/` |
| 历次改版和验收记录 | [历史 README](README.history.md)、`docs/`、`qa/`；记录中的版本只代表当次状态 |

## 架构与模块

页面使用 WXML、WXSS、JavaScript 和 JSON。`miniprogram/api/` 封装 HTTP、令牌及当前岗位；`utils/platform-*.js` 和页面内适配器将后端 DTO 转换成视图；业务数据的授权和状态变更由 B 平台服务判定。草稿、请求恢复记录等按各自生命周期保存在本地，不能替代后端业务事实。

| 模块 | 主要源码位置 | 当前职责 |
| --- | --- | --- |
| 登录、组织、资料 | `pages/account/`、`pages/profile/`、`api/auth.js`、`api/org.js` | 账号/微信登录、岗位切换、成员权限、资料和交接入口 |
| 创作 | `pages/creation/`、`pages/studio/`、`pages/ai-video/`、`api/franchise/content.js` | 文案、人物及克隆资源、视频任务、草稿、素材库、人工发布登记 |
| 获客与业务 | `pages/business/`、`pages/customer-flow/`、`pages/workflow/`、`api/franchise/crm.js` | 发布来源、客资、客户八步跟进、外部资料、正式报价与施工合同 |
| 灵感库与快速预算 | `design-migration/`、`pages/design/`、`api/franchise/design.js` | 公开户型、收藏、免费参考预算和授权历史；内部 AI 平面/立面等新生成已停用 |
| 外部委托与历史单 | `pages/external-entrust/`、`pages/external-handover/`、`pages/workflow/commission/` | 外部成果、冻结移交包与工程承接；存量原单按权限办理历史结算和必要移交 |
| 工程 | `pages/build/`、`api/franchise/engineering.js` | 计划、材料、现场记录、收款进度、交付与归档 |
| 财务与管理 | `pages/admin/`、`pages/management/`、`api/finance.js` | 收付款、退款、报销、经营摘要和绩效入口 |
| 公共能力 | `components/`、`styles/`、`utils/` | 页面组件、样式、导航、会话守卫、上传下载及恢复辅助 |

分包清单以 [app.json](miniprogram/app.json) 为准，打包排除以 [project.config.json](project.config.json) 为准。包检查同时核对资源引用和仓库设置的体积预算。

## 现行业务口径

八步名称固定为：**邀约进店、店面讲解、户型选择、带看工地、现场测量、别墅报价、合同签约、开工大吉**。第 04 步可由经办人填写原因并主动跳过，保存原因、经办人和时间；第 06 步使用正式报价与沟通事实，参考预算不会自动完成该步；第 07 步要求有效的正式施工合同，并锁定签约付款计划。第 08 步读取主工程实际开工事实，接收移交包和填写计划日期不等于开工。业务进度 8/8 不表示工程完工、交付、归档或无欠款。

新快速预算使用 `budget-free-v1`，不预占或扣除公司积分；已有付费预算中断单仍按原请求内容、原幂等键和原回执恢复。人物/声音克隆等内容业务保留原公司积分规则。费用实收来自真实收款原单及退款净额；历史模拟支付记录只供查询，不计实际收支。

外部委托沿用原客户、原合同、冻结成果及原工程。设计三岗已退出新授权；不能重新创建旧 LEGACY 委托、把所有在途旧单强制转换，或为已接收单重建工程。具体允许动作、历史结算前置、旧链接的只读落点及兼容写口退出证据，以[统一整改总表](docs/roadmap/V3-business-engineering-remediation-20261127.md)和[接口说明](miniprogram/api/README.md)为准；代码合并与目标环境验收分别记录。

## 开发与验证

使用 Node.js 24（与 CI 一致），在仓库根目录执行：

```bash
npm ci --no-audit --no-fund
npm run check
npm run check:role-homes
npm run check:packages
```

在微信开发者工具中导入**本仓库根目录**，由 `miniprogramRoot` 自动选择 `miniprogram/`。AppID 以项目配置为准，预览和上传需要相应开发权限。根目录 npm 依赖主要服务检查脚本；不要把它们整体复制进小程序包。

API 默认基址在 [env.js](miniprogram/config/env.js) 的 `API_BASE` 中配置，由 [http.js](miniprogram/api/http.js) 读取。[runtime-base.js](miniprogram/api/runtime-base.js) 只允许微信开发者工具使用本地 storage 覆写，真机及桌面微信使用环境默认入口。开发者工具联调时应指向隔离的 B 测试环境，并使用该环境真实账号与岗位。

带 `:native` 的脚本需要连接独立微信开发者工具项目；Node 页面/模板测试、原生模拟器操作、手机验收是不同证据，不能相互替代。上传开发版、启用体验版、提审及生产部署也应分别留回执。

## 现行代码与历史代码的边界

- 历史 `server/` 服务和 `demo:*` 命令不作为现行开发、生产启动或验收入口；清理情况按统一整改总表的 BIZ-T14 记录，不能按旧报告恢复失效入口。
- `utils/business.js`、`studio.js`、`engineering-records.js` 等混有历史本地模型和仍被调用的辅助函数。维护时先检查实际调用链和 API 会话分支，不按文件名整批删除，也不把本地模拟账本作为正式状态来源。
- 老入口和历史草稿可能仍有兼容逻辑；旧测试报告的“本地演示”“尚未接后端”不能描述今天整个平台。
- 实体费用的模拟支付新写已停用，保留原财务收付、退款、授权附件与审计。内容外部作品链接仍由人工登记；不能据此宣称平台已自动发布。

## 提交与交付

一个问题在独立分支开发，专项评审后单独 PR；`main` 与 `backend-main` 各自提交。涉及新增接口的修复，先使后端版本就绪，再发布配套小程序。各分支 CI 通过后，还需记录**同一次配对验收中的前后端完整 SHA**，不能仅拿两个不同时间的绿灯拼成发布结论。

数据库迁移、服务器配置和部署操作在后端分支维护；源码合并不代表已部署或已上传微信。真实服务状态以部署回执和对应环境验收为准。
