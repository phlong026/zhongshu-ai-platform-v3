# 微信一键登录最小修复就绪记录（2026-11-14）

## 根因与有效修复输入

- 当前员工小程序 AppID 与前端 `wxe584f6fad7b54487` 匹配。
- `zhongshu.service` 实际读取 `/opt/zhongshu/env.merged`；运行进程的 PF AppID/Secret 与该文件相同，排除文件更新未生效。
- 使用生产现有 PF 组调用微信官方 `stable_token`（`force_refresh=false`），返回 `40125`，未取得有效 token。
- 本地主仓 `众墅之家AI赋能平台/平台程序/.env` 存有同 AppID 的另一组 PF Secret，非空、非占位，与生产值不同；经 SSH 加密标准输入在服务器内存中向微信验证成功（`errcode=0`）。不需要重置密钥，也不需要借用其他 AppID 的凭据。
- 上述验证只记录布尔结果和错误码；未在日志、报告、命令参数或仓库持久化凭据及 access_token。
- 对照线上历史配置装配：员工微信配置从 PF 别名绑定到 `platform.identity.wechat.apps`；修复前还须排除命令行、JSON、Java系统属性或外部文件直接覆盖该配置的情况。
- 前端双 `wx.login` 属冗余调用，不会产生 `invalid appsecret`；本次不把无关改动混入凭据修复。

## 具体修复方案

1. 在服务器保留权限受限的原配置备份并记录指纹；保留当前 JAR、nginx、unit、数据库和其他 AppID 的配置。
2. 通过 SSH 加密标准输入把本地已验证有效的同 AppID Secret 传入服务器内存，只替换 `/opt/zhongshu/env.merged` 的 `PF_WX_STAFF_APPSECRET` 一项。既有 AppID 不变；检查配置文件其余键和元数据均保持原状；不打印值。
3. 员工微信配置 Bean 在进程启动时绑定，未发现热加载支持。需对共享 `zhongshu.service` 做一次受控重启才能生效；同 JVM 的设计/赋能 API、后台和调度器会短暂中断。不得并行启动第二个读写实例。
4. 检查服务终态、api/api2 健康与新启动日志，再用开发者工具微信一键登录。验收成功业务码、会话、有效任职和工作台，而不只验健康接口。
5. 若启动或健康失败，恢复原权限受限配置并重启恢复，核对原健康状态；若服务正常但出现新的登录错误，保留脱敏证据，按新错误继续定位，不把错误变更当作登录成功。
6. 微信登录成功后继续逐页点击，记录请求、控制台、业务错误和后端对应关系。

## 现有固定边界与待决事项

[独立 B 部署清单](../superpowers/plans/2026-11-14-b-independent-deployment.md) 第 14 行要求：

> 不替换 /opt/zhongshu/yudao-server.jar，不修改 A env、unit，不重启或停止 zhongshu.service

当前 `api2` 仍指向该共享服务。用户最新指令授权修复微信登录，但未明确是否允许为这一次修复例外修改共享 env 并重启 A/B 共用进程。最小修改和影响已明确；在边界例外确认前，不改该配置、不重启，也不通过替换包或另启读写实例绕过限制。

## 已完成检查与未完成项

- 已完成：本轮错误复现证据、实际配置来源与进程一致性、同 AppID 凭据新旧对照、微信官方接口验证、源码错误链与装配检查。
- 前端现有 `verify-wechat-login-official.cjs`、`verify-login-dual-channel.cjs` 均通过；仅为静态契约检查。
- 未完成：生产配置修改、重启、微信登录成功、登录后逐页与跨岗位真实联调。
