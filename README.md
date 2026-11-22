# 众墅之家 AI 赋能平台 · 微信小程序

本仓库采用两个长期分支：**`main` 保存原生微信小程序，`backend-main` 保存 Java 平台服务和 Vue 管理台**。小程序已通过平台 API 接入账号、客户、内容创作、设计委托、工程、财务和绩效；根目录的 `server/` 是历史联调服务，正式 B 平台入口在后端分支。

## 从哪里开始

| 目标 | 入口 |
| --- | --- |
| 小程序开发 | 当前 `main` 的 `miniprogram/` 与 `project.config.json` |
| 后端、总部管理台 | [backend-main](https://github.com/phlong026/zhongshu-ai-platform-v3/tree/backend-main) 的 `平台程序/` |
| 接口和会话约定 | [数据访问层说明](miniprogram/api/README.md)、[http.js](miniprogram/api/http.js) |
| 默认自动检查 | [package.json](package.json)、[Frontend CI](.github/workflows/frontend-ci.yml) |
| 部署和配对验收 | 后端分支的 [B 部署入口](https://github.com/phlong026/zhongshu-ai-platform-v3/tree/backend-main/平台程序/deploy/platform) 与 `平台程序/script/release-rehearsal/` |
| 历次改版和验收记录 | [历史 README](README.history.md)、`docs/`、`qa/`；记录中的版本只代表当次状态 |

## 架构与模块

页面使用 WXML、WXSS、JavaScript 和 JSON。`miniprogram/api/` 封装 HTTP、令牌及当前岗位；`utils/platform-*.js` 和页面内适配器将后端 DTO 转换成视图；业务数据的授权和状态变更由 B 平台服务判定。草稿、请求恢复记录等按各自生命周期保存在本地，不能替代后端业务事实。

| 模块 | 主要源码位置 | 当前职责 |
| --- | --- | --- |
| 登录、组织、资料 | `pages/account/`、`pages/profile/`、`api/auth.js`、`api/org.js` | 账号/微信登录、岗位切换、成员权限、资料和交接入口 |
| 创作 | `pages/creation/`、`pages/studio/`、`pages/ai-video/`、`api/franchise/content.js` | 文案、人物及克隆资源、视频任务、草稿、素材库、人工发布登记 |
| 获客与业务 | `pages/business/`、`pages/workflow/`、`api/franchise/crm.js` | 发布来源、客资、客户、方案、合同相关入口 |
| AI 设计 | `design-migration/`、`pages/design/`、`api/franchise/design.js` | 公开户型、AI 平面/立面、预算；由 B 的设计适配调用设计能力服务 |
| 设计委托 | `pages/workflow/commission/`、`api/franchise/commission.js` | 委托、费用和成果、工程移交；保留明确标识的模拟支付流程 |
| 工程 | `pages/build/`、`api/franchise/engineering.js` | 计划、材料、现场记录、收款进度、交付与归档 |
| 财务与管理 | `pages/admin/`、`pages/management/`、`api/franchise/finance.js` | 收付款、退款、报销、经营摘要和绩效入口 |
| 公共能力 | `components/`、`styles/`、`utils/` | 页面组件、样式、导航、会话守卫、上传下载及恢复辅助 |

分包清单以 [app.json](miniprogram/app.json) 为准，打包排除以 [project.config.json](project.config.json) 为准。包检查同时核对资源引用和仓库设置的体积预算。

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

- `server/design-collaboration/` 等 Node 服务及 `demo:*` 命令保留作历史模拟或回归；它们不是 B 平台生产服务。
- `utils/business.js`、`studio.js`、`engineering-records.js` 等混有历史本地模型和仍被调用的辅助函数。维护时先检查实际调用链和 API 会话分支，不按文件名整批删除，也不把本地模拟账本作为正式状态来源。
- 老入口和历史草稿可能仍有兼容逻辑；旧测试报告的“本地演示”“尚未接后端”不能描述今天整个平台。
- 支付模拟和人工登记外部作品链接是当前明确保留的业务边界；没有代码和渠道验收依据时，不把它们描述成真实支付或平台自动发布。

## 提交与交付

一个问题在独立分支开发，专项评审后单独 PR；`main` 与 `backend-main` 各自提交。涉及新增接口的修复，先使后端版本就绪，再发布配套小程序。各分支 CI 通过后，还需记录**同一次配对验收中的前后端完整 SHA**，不能仅拿两个不同时间的绿灯拼成发布结论。

数据库迁移、服务器配置和部署操作在后端分支维护；源码合并不代表已部署或已上传微信。真实服务状态以部署回执和对应环境验收为准。
