# 众墅之家 AI 赋能平台

总部与加盟门店协同的赋能平台。一个仓库管三端：小程序、Web 管理后台、后端。

## 目录

| 目录 | 内容 |
|---|---|
| `小程序/` | 微信原生小程序（AppID `wxe584f6fad7b54487`）。业务、权限、流程目前都在本地实现，数据存在手机本地；说明见 `小程序/README.md`，业务规则见 `小程序/docs/` |
| `管理后台/` | Vue3 + Element Plus Web 管理后台（复制自众墅设计小程序，待改造） |
| `后端程序/` | yudao（Spring Boot）后端，分三层，见下 |
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
- 点数、充值、激活码的去留与 AI 是否计费一起在 M1 决定，目前代码原样保留。
- 小程序尚未接后端。

## 本地构建

后端需要 JDK 17（`JAVA_HOME` 指向 JDK 17；Homebrew 安装的路径如下）：

```bash
cd 后端程序
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -DskipTests package
```

后端测试需要 Docker（Testcontainers 启动 PostgreSQL）。本机如开启了系统 SOCKS 代理，JVM 会继承它导致连不上测试数据库，需要显式关掉：

```bash
cd 后端程序
mvn test -DargLine="-DsocksProxyHost= -DsocksNonProxyHosts=localhost|127.*|[::1]"
```

管理后台：

```bash
cd 管理后台
pnpm install
pnpm dev
```

小程序：用微信开发者工具导入 `小程序/` 目录。

## 约定

- 环境变量实际值不入库，只提交 `.env.example`。
- 提交信息使用中文，遵循 Lore 提交协议。
