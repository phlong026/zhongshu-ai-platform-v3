# T23 B 后端与小程序真实联调

本批次只连接 B 的本地 SSH tunnel `127.0.0.1:58081` 和 B 小程序 DevTools AppID `wxe584f6fad7b54487`；未修改 A，未发送短信，未调用 AI 外部生成。

## 已通过

- 真实管理员登录成功，账号为 `userId=145`；完整 token、登录响应和手机号只保存在仓库外 `0600` 私有目录。
- 总部 `orgId=118`：老板岗和设计行政岗已就位，真实岗位上下文通过。
- 门店 `orgId=119`：老板岗和销售岗已就位；公司积分池仅开户，真实余额为 `0`，没有付款、调额或伪造审批。
- A 公开案例经 B 只读代理返回 20 条；选定案例的员工收藏在 B 中完成 `0 → 1 → 0`，取消后已恢复原状。未调用 A 收藏接口。
- DevTools “我的”页对 B 发出 7 次真实请求，全部 HTTP 200 / `code=0`。
- 从老板工作台真实点击“经营简报”，快照和经营分析两个 B API 均 HTTP 200 / `code=0`，页面 `ready=true`。
- DevTools 最终保持在门店老板工作台：`orgId=119`、`postId=2121620613810364417`。

## 当前边界

- Provider 预算规则查询返回 `code=500`；根因已确认为 B Runtime 缺少真实供应商凭据，导致 Provider readiness 不能通过。已停止重试，未填假 Key，不把该路径记为通过。
- 未执行付费预算完成、AI 生成、短信发送或外部消息。

## 证据索引

- `http-business-initialization.json`：组织、岗位和零余额积分池。
- `case-favorite-live-result.json`：A 公开读 + B 收藏写的恢复性闭环。
- `devtools-profile-live-result.json`：小程序真实 B 请求。
- `boss-entry-live-result.json` / `经营简报真实入口.png`：老板工作台真实点击。
- `devtools-store-default-result.json` / `门店老板默认工作台.png`：最终默认联调身份。
- `budget-provider-blocked.json`：预算链路的真实阻塞点和零付费边界。

## 服务器运行状态与连接

B API 已在 `124.223.61.18` 运行，服务 `zhongshu-platform-b-integration.service`，仅监听 `127.0.0.1:48081`。当前电脑的加密 SSH 转发地址为 `http://127.0.0.1:58081/admin-api`；当前开发者工具已设置该 B 基址和真实会话，可直接点测。钱包接口使用同一 B 入口的 `/app-api`。

这是隔离联调环境，未切换正式构建默认域名，未开公网测试端口，也未激活 B 的 DNS/TLS 公网入口。因此当前证据覆盖本机开发者工具，尚未覆盖手机真机公网访问。

- 独立数据库/缓存项目：`zhongshu-platform-b-integration`，四个容器仅使用 B 目录，端口只绑定回环地址。
- 数据：`/mnt/datadisk0/zhongshu-platform-b-integration`；代码：`/opt/zhongshu-platform-b-integration/release`。
- 外置配置：`/etc/zhongshu-platform-b-integration`，root 私有。API 使用 systemd credentials，只得到自身运行配置，不得到迁移 owner 密码。
- 当前运行的 API JAR SHA：`831e5ffcd3e534a2a534d3420b92e3a2e43f5d81614d57c070c0c9da184268e8`。源码为 `a6bb8af9`（隔离构建来源 ec586bca + 短信终态补丁）。
- Provider JAR SHA：`a351b8a98c200f141d34f24f018eb1883a249391cc4061e2b5a8e948f4ef713f`。实际执行 70 条 Provider 迁移，B API 执行 78 条迁移。
- Provider 的进程因 Runtime 缺真实供应商凭据而暂停并取消自动启动，避免反复重启；它的独立数据库、TLS 和映射配置已保留。未提供假 API Key。
- Provider 测试目录包含六项内部成本测试值、一条 `LOCAL_IT / FRAME / STANDARD` 测试预算规则（1000–1500 元/㎡），明确是 NON_PRODUCTION fixture，不能当真实供应商成本或业务报价。没有给公司积分池加测试余额，更未修改 A 积分。

## 验证与回退

- A 的 JAR、两份 Nginx 站点配置 SHA 以及两个 A 服务启动时间与执行前一致；A API、A Runtime、B API 均 HTTP 200 / UP。见 `a-protection-final-comparison.json`。
- 两个 B 数据库已备份到当前电脑私有目录，并在无网络的独立本地 PostgreSQL 容器中恢复成功；分别验证 78、70 条迁移。见 `database-component-backup-restore.json`。这是数据库组件恢复测试，不是全系统冻结灾备演练。
- 新盘、KMS、快照等新增付费资源开通数为 0；A Nginx 未 reload，未改 A 数据库、服务 unit、COS 或前端入口。
- 如需停止本轮环境，只操作两个 `zhongshu-platform-b-integration*.service` 和 Docker project `zhongshu-platform-b-integration`；禁止使用针对 A 的 stop/restart 命令，禁止 `down -v`。停止不删除数据。
- 本机前端改动仅为 DevTools storage，会话前值保存在仓库外私有 `devtools-selected-storage-before.json`；未改正式 `config/env.js` 的默认地址。

## 仍需配置才能测试

真实 AI 出图/润色、完整预算 Provider 和内容供应商外呼缺少有效供应商凭据。国内短信签名为零，联调 profile 明确关闭短信发送与短信登录；真实微信和账号密码认证仍启用，mock 登录被硬拒绝。公司积分初始余额为 0，付费业务还须按总部正常开通/调点流程准备积分。
