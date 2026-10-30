# 众墅之家 AI 赋能平台

总部与加盟门店协同的赋能平台。一个仓库管两端：Web 管理后台、后端。微信小程序在独立仓库 `zhongshu-ai-platform-v3` 开发，本仓库原归档的小程序快照已删除（历史提交中仍可追溯）。

## 目录

| 目录 | 内容 |
|---|---|
| `平台程序/` | yudao（Spring Boot）后端 + Vue3 管理后台，分三层，见下 |
| `平台程序/admin-ui/` | Vue3 + Element Plus Web 管理后台（基于 yudao-ui-admin-vue3 底座改造） |
| `项目文档/平台/` | 平台层方案与约定，含 [后台改造方案](项目文档/平台/后台改造方案-2026-10-21.md) |
| `项目文档/加盟协同域/` | 加盟协同域的方案、接口约定与验收记录 |

### 后端分层

| 层 | 位置 | 说明 |
|---|---|---|
| 框架底座 | `yudao-framework/`、`yudao-module-system/`、`yudao-module-infra/` | yudao 原有，原则上不改 |
| 平台能力层 | `platform/` | 组织、登录、授权、待办、审批、文件、通知、AI 等通用能力，M1 起加入 |
| 业务域层 | `domains/` | 每个业务域一个目录；新建业务域按 `domains/_template/README.md` |
| 旧业务模块 | `yudao-module-identity/`、`design/`、`commerce/`、`ai-orchestration/` | 众墅设计小程序原有业务，M1 起拆分迁移或删除 |

## 当前进度

- M0 结构整理：已完成目录调整、建仓库、建分层骨架与业务域模板。
- 计费能力去留已决定（2026-10-28，用户确认）：点数、充值、激活码**保留**，AI 对加盟商**继续计费**。commerce/identity/design/ai-orchestration 不再按原方案删除，改为保留并逐步收编进平台层；franchise-design（AI 平面/立面/预算）由此解锁，排入后续里程碑。
- 小程序尚未接后端。

## 本地构建

后端需要 JDK 17（`JAVA_HOME` 指向 JDK 17；Homebrew 安装的路径如下）：

```bash
cd 平台程序
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -DskipTests package
```

后端测试需要 Docker（Testcontainers 启动 PostgreSQL）。本机如开启了系统 SOCKS 代理，JVM 会继承它导致连不上测试数据库，需要显式关掉：

```bash
cd 平台程序
mvn test -DargLine="-DsocksProxyHost= -DsocksNonProxyHosts=localhost|127.*|[::1]"
```

管理后台：

```bash
cd 平台程序/admin-ui
pnpm install
pnpm dev
```

小程序：在独立仓库 `zhongshu-ai-platform-v3` 用微信开发者工具导入。

## 约定

- 环境变量实际值不入库，只提交 `.env.example`。
- 提交信息使用中文，遵循 Lore 提交协议。
- 已合入的 Flyway 迁移脚本只增不改：重命名或改内容必须换新版本号，避免已执行过旧版的环境启动失败（2026-10-28 评审 P1-1）。
