# 众墅之家 AI 赋能平台 · 后端与管理台

本仓库用两个长期分支维护三端：**`main` 是原生微信小程序，当前 `backend-main` 是 Java 后端与 Vue 管理台**。小程序已接入平台 API；“独立小程序仓库”“小程序尚未接后端”是历史描述，不代表当前结构。

## 当前运行入口

| 组件 | 源码 | 作用 |
| --- | --- | --- |
| B 赋能平台 API | `平台程序/yudao-platform-server/` | 总部与门店的账号、岗位、创作、客户、委托、工程、财务与绩效，应用默认端口 48080；仓内 Compose 映射到宿主机 48081 |
| Web 管理台 | `平台程序/admin-ui/` | Vue 3 / Element Plus 管理台，与 B API 配套 |
| 微信小程序 | [同仓库 main 分支](https://github.com/phlong026/zhongshu-ai-platform-v3/tree/main) | 原生 WXML / WXSS / JS，调用 B 的授权接口 |
| 设计应用及能力 | `平台程序/yudao-server/`、`yudao-design-provider/`、`design-capability-api/`、`ai-runtime-b/` | 既有设计应用与独立能力侧；根据具体部署组合配置，不能替代 B 启动包 |

## 后端分层

项目使用 Spring Boot、PostgreSQL、Flyway 和 Redis，按模块组织，B 的业务服务在一个应用启动包内装配。

| 层 | 位置（均在 `平台程序/` 内） | 责任 |
| --- | --- | --- |
| 框架和系统基础 | `yudao-framework/`、`yudao-module-system/`、`yudao-module-infra/` | Web、安全、MyBatis、用户、基础设施 |
| 平台公共能力 | `platform/`、`yudao-platform-operations/` | 组织岗位、认证授权、命令幂等、工作流、点数、待办和运营 |
| 加盟业务域 | `domains/franchise/franchise-*` | org、crm、sales、commission、engineering、finance、performance、content、asset、design |
| 独立启动与组合 | `yudao-platform-server/`、`deploy/platform/` | B 的依赖装配、配置、交付与恢复 |
| 既有设计模块 | `yudao-module-identity/`、`yudao-module-design/`、`yudao-module-commerce/`、`yudao-module-ai-orchestration/` | 仍有实际构建或能力侧用途，不能视为已经迁走或可以整批删除 |

B 实际装配范围以 [启动模块 POM](平台程序/yudao-platform-server/pom.xml) 和 [构建边界脚本](平台程序/script/platform/build-platform.sh) 为准。父 POM 的全部模块清单不等于 B 的运行依赖；A/B 的组织、数据及文件边界由各适配器与部署配置约束。

## 开发入口

后端使用 JDK 17；Maven Wrapper、Java 版本和依赖以仓库文件为准。B 的完整校验和产物边界检查：

```bash
cd 平台程序
bash script/platform/build-platform.sh
```

该脚本执行选定 reactor 的 `verify`，包括测试；需要 PostgreSQL / Redis 测试条件，不能把它理解为跳过测试的快速打包命令。后端 CI 的服务版本、测试清单和连接参数见 [backend-ci.yml](.github/workflows/backend-ci.yml)。

管理台使用 Node.js 24（与 CI 一致）及 [package.json](平台程序/admin-ui/package.json) 固定的 pnpm 版本：

```bash
cd 平台程序/admin-ui
npx --yes pnpm@11.19.0 install --frozen-lockfile
npm run dev
```

开发模式和 API 目标按管理台环境配置设置；生产交付不能复用个人开发环境文件。进一步说明见 [后端运行指南](平台程序/README.md)。

## 部署、验收和历史材料

- [B 部署目录](平台程序/deploy/platform/README.md) 是正式运维入口；配置模板、最终 Compose 组合和恢复流程在该目录维护。
- `平台程序/script/release-rehearsal/` 与 [配对工作流](.github/workflows/paired-release.yml) 在临时运行器验收具体前后端组合，保留不可变包、提交 SHA 与迁移/回滚证据。
- `项目文档/` 与 `平台程序/docs/` 包含历次设计和验收记录；日期较早的计划不覆盖当前源码、配置或部署证据。
- 旧根入口存入 [README.history.md](README.history.md)，旧应用启动说明存入 [README.legacy.md](平台程序/README.legacy.md)。历史记录保留追溯，不作为 B 的默认启动命令。

按问题独立开发、专项评审和单独 PR 合并。Flyway 已合入迁移不原地改名或改内容，修正使用新版本迁移。密钥和实际环境文件不入库。代码合并、演练通过和生产部署是三个不同状态；当前环境状态必须查看对应部署回执。
