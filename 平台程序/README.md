# 众墅之家平台后端 · B 运行指南

本目录所在分支为 `backend-main`；配套小程序在同仓库 `main`。总体模块与三端关系见 [仓库入口](../README.md)。

## 构建 B 平台

当前 B 启动模块是 [yudao-platform-server](yudao-platform-server/pom.xml)，应用默认端口 **48080**（`PF_SERVER_PORT` 可覆盖）；仓内 B Compose 使用 `127.0.0.1:48081:48080` 映射，宿主机联调入口为 **48081**。`yudao-server` 是既有设计应用入口；`compose.dev.yml` 和 `script/dev/` 的历史本地流程也不等于独立 B 部署。

准备 JDK 17、仓库 Maven Wrapper 所需网络以及可用的测试数据库/缓存条件，然后在本目录执行：

```bash
bash script/platform/build-platform.sh
```

脚本先校验发布边界，再对 `yudao-platform-server` 及其依赖运行 `verify`，最后检查生成 JAR，路径为 `yudao-platform-server/target/yudao-platform-server.jar`。不会跳过测试。具体 CI 用例和 PostgreSQL / Redis 参数见 [Backend CI](../.github/workflows/backend-ci.yml)；它同时执行财务/内容完整套件、发布回归和管理台上传回放。

## 数据与业务边界

| 目录 | 关注点 |
| --- | --- |
| `platform/platform-org`、`platform/platform-authz`、`platform/platform-identity` | 租户、组织、岗位、登录和授权 |
| `platform/platform-command`、`platform/platform-workflow`、`platform/platform-wallet` | 幂等命令、审批工作流和组织点数 |
| `domains/franchise/franchise-content`、`franchise-asset` | 内容任务、草稿、素材与文件访问 |
| `domains/franchise/franchise-crm`、`franchise-sales`、`franchise-commission` | 客户责任、合同、设计委托和工程接收 |
| `domains/franchise/franchise-engineering`、`franchise-finance`、`franchise-performance` | 工程履约、资金记录、绩效归属 |
| `domains/franchise/franchise-design` | B 的设计适配与公开户型访问 |
| `yudao-module-*`、`yudao-design-provider`、`design-capability-api` | 框架、既有应用或独立能力组件，按各自实际依赖维护 |

鉴权、组织范围、业务状态及文件可读性在服务端核验。前端提交的 ID 和展示文案不能代替这些约束；历史经办人和真实垫付人也不能随当前员工责任转移而改写。

## 内容任务和作品标题

`POST /admin-api/franchise/content/tasks`、`GET /admin-api/franchise/content/tasks` 和任务详情返回顶层 `title`。链接解析、语音转写和文案改写的创建响应也包含 `title`。标题采用 `类型｜输入主题`，整条最多 24 个 Unicode 码点。数字人口播从当次正文取首个可用行，AI 视频取描述，文案改写取来源文案。缺少安全的文本来源时显示 `类型｜未命名创作`。派生标题仅向任务作者返回，同组织其他查询者看到类型兜底。输入快照继续保留在服务端。

`POST /admin-api/franchise/content/tasks/{id}/save-work` 优先使用显式作品标题。未填写时沿用任务命名规则。`GET /admin-api/franchise/content/drafts` 为标题为空的历史作品生成显示标题。与作品类型匹配的旧系统默认名在有可用任务主题时也会更新展示。列表回显不写入数据库，其余已保存的标题直接展示原名。

## 管理台与部署

管理台源码在 [admin-ui](admin-ui/)，Node/pnpm 命令见仓库入口；环境样例及构建模式由该目录维护。

独立 B 的 Compose、配置示例、能力叠加文件和运维脚本在 [deploy/platform](deploy/platform/README.md)。部署前应核验**叠加后的最终配置**、文件卷、外部适配器和迁移状态，按现有运维手册执行。演练产物及恢复测试由 `script/release-rehearsal/` 生成，不代表已对生产进行任何操作。

源码测试可使用边界替身或隔离数据；真实微信/短信、对象存储和 AI 供应商的可用性须在对应环境另行验证。模拟支付仅是明确配置的业务流程，不应作为真实渠道付款凭据。

## 维护约定

- 业务改动提交到对应域，跨域通过已有接口及应用装配协作。
- 已发布的 Flyway 迁移保留原内容；修复以新迁移推进。
- 保留每次候选的前后端完整 SHA、自动检查结果、部署回执和人工验收边界。
- 历史 A 启动命令、旧模块清单和历次进度见 [README.legacy.md](README.legacy.md)，只供追溯。
