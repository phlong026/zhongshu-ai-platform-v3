# 微信一键登录运行时只读诊断（2026-11-14）

> 修复前的运行时快照。后续登录修复已执行，当前结论见 [登录修复记录](wechat-login-fix.md) 和 [四岗位实测](live-integration-results.md)。

## 已确认结论

开发者工具的登录请求发往 `https://api2.zszhj.com/admin-api/platform/auth/wechat-login`。主代理已观察到 HTTP 200、业务码 `1104000002`、提示“微信服务暂不可用，请稍后重试”。本次通过已有 SSH 只读连接核对生产日志，确认该错误出现在微信 `code2session` 阶段，上游返回 `errcode=40125`。

| 服务日志时间（北京时间） | 阶段 | 微信返回码 |
|---|---|---|
| 2026-11-14 17:19:15.489 | code2session | 40125 |
| 2026-11-14 17:20:45.321 | code2session | 40125 |

证据来源：`124.223.61.18` 的 `journalctl -u zhongshu.service --since '-35min' --no-pager -o cat`。采集后只提取时间、调用阶段、errcode 和异常类型，没有在报告中记录手机号、登录 code、token、AppSecret 或其他凭据值。40125 对应 invalid appsecret；参见 [腾讯云 CloudBase 官方 FAQ](https://docs.cloudbase.net/en/integration/wechat-official-oauth)。该 FAQ 属公众号接入，作为错误码释义的辅助官方证据；本次小程序根因阶段由上述实际 code2session 日志确定。微信 code2Session 官方页面本轮无法直接抓取。

## 当前请求实际到达的运行实例

本次重新读取生效 nginx 配置，确认 `server_name api2.zszhj.com` 的业务反代目标为 `http://127.0.0.1:48080`。

- 服务：`zhongshu.service`，`active/running`。
- PID：`1676621`。
- 启动时间：`2026-11-10 17:05:28 CST`。
- JAR：`/opt/zhongshu/yudao-server.jar`。
- JAR 大小：`186184621` 字节。
- JAR 修改时间：`2026-11-10 17:05:05.437218972 +0800`。
- JAR SHA256：`a5e05c5b1b6317b3022c3c3057f45696a0222c0e9f9d532aa26609ae57401e91`。

以上实时状态与今早 10:06–10:08 的服务器只读盘点一致。现有 `api2` 指向旧的设计/赋能融合服务，不能把本次请求称为当前独立 B 工作树或本地 18082 临时实例的联调结果。

当前独立 B 源码中 `deploy/nginx-api2.conf:10` 的 `48081` 是仓内模板，不是当前服务器生效目标。前端仓库 `docs/superpowers/plans/2026-11-14-b-independent-deployment.md:18,28,34,48` 已说明保留现有 api2 路由、为独立 B 采用新站点，建议域名尚不是已创建资源。

## 配置存在性

只对 `/opt/zhongshu/env.merged` 输出键名及非空状态，未展示凭据值。以下键均存在且非空：

- `PF_WX_PROVIDER`
- `PF_WX_STAFF_APPID`
- `PF_WX_STAFF_APPSECRET`
- `ZS_WX_APPID`
- `ZS_WX_APPSECRET`
- `ZHONGSHU_IDENTITY_WECHAT_PROVIDER`
- `WX_MP_APPID`
- `WX_MP_SECRET`

文件修改时间为 `2026-11-08 21:23:44.788021090 +0800`。本次未读取各凭据值、未核验实际进程加载了哪组值，亦未对任何值进行修改或轮换。现有证据定位到微信拒绝服务端使用的 AppSecret；具体失效值及配置覆盖来源仍待修复时核对。

## 本地临时实例日志不能用来解释本次错误

本地 Java 标准输出：

- PID 21072、18081：`/private/tmp/zs-ab-http-20261111-unmypre8/logs/a-api-restart1.log`。
- PID 35445、18082：`/private/tmp/zs-ab-http-20261111-unmypre8/logs/b-api-restart2-finaljar.log`。

两个日志扫描均未匹配 `code2session`、`stable_token`、`getuserphonenumber`、`RealWechatStaffClient` 或 `1104000002`。生产服务日志则有上述两次明确匹配，故应以生产实例的错误与部署版本解释当前开发者工具结果。

## 限制与后续验证

1. 目前已定位到 `code2session` 上游错误；没有证据表明执行到了 `stable_token` 或手机号兑换阶段。
2. 单个通用业务码 `1104000002` 本身不能区分所有根因；本次生产日志的 `40125` 是更精确的证据。
3. 未修改生产文件、数据库、服务配置，未重启、部署或轮换凭据。
4. 登录未成功，因此登录后各岗位页面的真实请求仍不能算通过。

## 代码参考

独立 B 工作树：`/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/.worktrees/b-independent-20261114/平台程序`。

- `platform/platform-identity/src/main/java/cn/iocoder/yudao/module/platform/identity/wechat/RealWechatStaffClient.java:80-90`：`code2session` 上游响应处理。
- 同文件 `:123-131`：`stable_token` 响应处理。
- 同文件 `:96-107`：手机号兑换响应处理。
- 同文件 `:137-159`：网络、超时、非 200 和解析异常转换。
- 同文件 `:163-164`：缺少 AppID 对应 secret 时产生另一业务错误，不与本次生产 `40125` 混同。

这些行号是当前独立源码参考，不能据此声称线上 JAR 与当前工作树完全一致。
