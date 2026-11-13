# 众墅之家赋能平台管理后台（B）

当前 `platform-split` 候选是用户确认的“总部运营工作台”，包含总部运营、组织开通和员工门户。不得以旧“设计管理后台”（A）的画面或运行结果作为本项目验收。

| 对象 | 当前B入口与源码 | 核对方式 |
|---|---|---|
| 总部管理后台 | `/index` → `src/views/platform/home/index.vue` | 页面标题“总部运营工作台”，总部运营与组织开通菜单 |
| 总部运营 | `/platform-ops/*` → `src/api/platform/operations.ts` | 接口 `/admin-api/platform/ops/*`，总部权限校验 |
| 员工门户 | `/platform-login`、`/fr/*` | 员工短信身份及岗位，不代替总部验收 |
| 后端制品 | `yudao-platform-server` | B默认端口48081，独立数据库与Redis |

`src/views/zs/` 中仍有历史A代码，仅复用已明确保留的组织配置组件；入口注册与构建边界由测试约束。历史 `yudao-server`、48080及“设计管理后台”不作为本轮B运行候选。

## 本地运行

Node `>=24.14.1 <25`，pnpm `11.19.0`。在本工作树执行：

```bash
pnpm install --frozen-lockfile
cp .env.example .env.env.local
pnpm dev --port 5186 --strictPort
```

开发默认将 `/admin-api` 代理至 `http://127.0.0.1:48081`；如使用另一套隔离B后端，在本地环境文件设置 `VITE_PROXY_TARGET`。不得仅凭端口猜测版本，应核对进程工作目录、Git分支、制品SHA、页面标题与API目标。启动新候选时使用空闲端口，不停止其它工作区的服务。

无私有环境文件时，API默认为同源 `/admin-api`，品牌标题默认为“众墅之家赋能平台管理后台”。部署需由反向代理将该路径转发给同一候选B后端。需要环境覆盖时从 `.env.example` 创建对应文件；仓库不存放实际 `.env.prod`、密钥或令牌。所有 `VITE_*` 变量均会暴露到浏览器，不可放入机密。

## 验证

```bash
node --test tests/*.test.js
pnpm ts:check
pnpm lint
pnpm build:prod
pnpm audit
```

总部与员工两套角色都要检查空态、失败、无权限、会话失效及迟到响应。自动测试和构建不替代真实角色、浏览器与渠道验收。本轮协议变更、分支与验收限制见 [联合审查变更记录](../docs/joint-review-20261114/README.md)。

`package.json`、锁文件与 `pnpm-workspace.yaml` 一起维护。只允许已登记的安装脚本；保留供应链发布年龄门禁。项目沿用 RuoYi-Vue-Pro / vue-element-plus-admin 底座，许可证见 [LICENSE](LICENSE)。
