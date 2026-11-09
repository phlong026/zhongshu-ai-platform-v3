# Web 管理后台联调证据（2026-11-09 凌晨）

Playwright MCP 调试「众墅之家设计管理后台」（admin-ui，生产 api2 链路）过程留证。

- `admin-ui-login.png` — 登录页报「请求出错 502」的失败现场（当时后端链路未通）。
- `captcha-1.png` — 已登录进工作台的界面：案例库 / 资金与点数 / 预算与报价菜单，近 7 日任务图表。

同轮调试的 console log 与页面快照在 `.playwright-mcp/`（已加入 .gitignore，不入库）。
