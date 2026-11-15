# ⚠️ 身份与启动对照（先读这里，2026-11-14）

| | 本目录（平台程序/admin-ui） | 拆分交付树 admin-ui |
|---|---|---|
| 身份 | **A 设计运营后台**（切流前合并形态，含历史页） | **B 赋能平台后台**（卡片式运营工作台/经营看板、identity、org-staff、wallet） |
| 配对后端 | 旧融合 `yudao-server` **:48080**（切流后仅服务 A） | `yudao-platform-server` **:48081**（独立库 `zhongshu_platform_b`） |
| 本地启动 | 本目录 `npm run dev`（代理默认 48080） | 拆分树 `平台程序/admin-ui` `npm run dev`（`.env.local` 已指向 48081） |
| 生产 | 当前 admin-dist 由本形态部署 | 切流授权后按交付说明部署 |

**教训（2026-11-13/14）**：曾误用本目录起「B 后台」→ 品牌/菜单/数据全错，还误连过生产。
启动任何后台前先确认：目标环境 + 对应目录。B 后台开发/验收一律走拆分树。

---

# 众墅之家设计管理后台

众墅之家设计平台的运营管理端，基于 Vue 3、Vite、TypeScript、Element Plus 和 RuoYi-Vue-Pro 管理端底座改造。

## 当前范围

业务代码集中在：

- `src/views/zs/`：工作台、案例、审核、授权码、充值方案、订单、点数流水、C 端用户、AI 任务、导出和审计。
- `src/api/zs/index.ts`：众墅管理端 API。
- `src/router/modules/zs.ts`：众墅静态业务路由。

底座的 system/infra 页面仍在仓库中，但众墅业务完成度只按上述 `zs` 代码面统计。

2026-11-09 新增员工身份配置工作区：`/platform-login` 员工短信登录、`/fr/identity` 有效岗位选择、`/fr/org/staff` 本组织部门/员工/任职与离职交接。员工门户仅使用当前 StaffPost 能力；全局组织开通仍由平台授权人员办理。接口、验证结果及尚未进行的生产/手机验收见 [身份配置实现记录](../docs/web-admin-design/身份配置Web实现与验收-20261109.md)。

## 工具版本

- Node.js：建议使用已有 Node 24（本轮24.14.1）；当前 pnpm 11 至少需要22.13，部分现有测试依赖也需要较新运行时。
- pnpm：`11.19.0`（由 `package.json#packageManager` 固定）

项目级 `.npmrc` 固定使用 npm 官方 registry；`pnpm-workspace.yaml` 仅允许锁文件中 3 个明确登记的传递依赖执行安装脚本。

## 环境配置

复制 `.env.example` 为本地环境文件，并按实际环境填写：

```dotenv
NODE_ENV=development
VITE_APP_TITLE=众墅之家设计管理后台
VITE_BASE_PATH=/
VITE_BASE_URL=
VITE_API_URL=/admin-api
VITE_UPLOAD_TYPE=server
VITE_APP_TENANT_ENABLE=false
VITE_APP_CAPTCHA_ENABLE=true
VITE_APP_DOCALERT_ENABLE=false
VITE_OPEN=false
VITE_PORT=5173
```

不要提交真实环境地址、账号、令牌或其它凭据。

## 常用命令

```bash
pnpm install --frozen-lockfile
pnpm dev
pnpm ts:check
pnpm lint
pnpm build:prod
```

`build:prod` 使用已入库的 `.env.prod` 安全默认值；部署环境可按目标域名覆盖公开的 Vite 构建变量。不要把令牌、密钥或其它机密放入 `VITE_*` 变量。

## 出图价格管理

「众墅运营 → 生成价格」位于 `/zs/generation-pricing`。平面、立面分别按 2K / 4K 配置每张设计点、允许张数和生效时间。调整价格会新增规则；停用保留历史记录，不改变已创建任务的冻结价格。

现有价格迁移为 2K；4K 初始不设价格，运营人员配置生效规则后才能生成。查询与管理权限分别为 `commerce:generation-price:query`、`commerce:generation-price:manage`。接口说明见[出图规格与计价 API](../后端程序/docs/zhongshu-design/04-出图规格与计价API-2026-09-29.md)。

## 质量要求

- 冻结安装、TypeScript 检查、Lint 和生产构建必须由根级 CI 执行。
- 关键路由、API 参数、权限态、19 位 ID、金额和时间格式必须有自动化测试。
- 空数据、无权限、登录失效、网络错误和服务错误不得通过空 `catch` 静默吞掉。
- 视觉验收证据必须登记视口、fixture、提交号和可取回制品。

## 上游与许可证

- 管理端底座来源：RuoYi-Vue-Pro / vue-element-plus-admin 生态。
- 本目录的上游许可证见 [`LICENSE`](LICENSE)。
- 本项目源码来源与归档完整性见根目录 [`SOURCE_PROVENANCE.md`](../SOURCE_PROVENANCE.md)。
- 当前任务与验证状态见 [`项目状态看板`](../项目文档/项目状态看板.md)。
