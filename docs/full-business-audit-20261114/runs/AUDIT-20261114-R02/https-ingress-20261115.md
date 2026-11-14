# platform.zszhj.com HTTPS 接通与微信域名实测

日期：2026-11-15，Asia/Shanghai。关联：[原排查方案](../../README.md)、[前期环境准备](preflight.md)。

## 已完成的域名与服务端工作

用户指定使用 `platform.zszhj.com`，并要求保留微信合法域名校验。恢复操作时权威 DNS 已返回以下记录，本轮核对后复用，没有重复新建；不能据此声称该记录由本轮操作创建。

| 项目 | 实际结果 |
|---|---|
| 主机记录 | `platform` |
| 类型及记录值 | A → `124.223.61.18` |
| TTL | 600 |
| API 基址 | `https://platform.zszhj.com/admin-api` |
| 证书 | Let's Encrypt，SAN 为 `platform.zszhj.com` |
| 有效期末 | 2027-02-13 06:33:39，北京时间 |
| 独立站点文件 | `/etc/nginx/conf.d/zhongshu-platform-b-integration.conf` |
| B 上游 | `127.0.0.1:48081`，保留原 API 路径 |

沿用现有 ACME 账号，以 webroot 签发本域名证书。保留 HTTP 的 ACME challenge 路径，其余 HTTP 请求以 308 跳转本域名 HTTPS。配置复用 B 的候选站点模板，经独立只读审查、完整 `nginx -t` 和优雅 reload 后启用。可审查副本见 [platform-ingress.nginx.conf](platform-ingress.nginx.conf)。

已通过 `certbot reconfigure` 的续期模拟，并为本证书设置续期后执行 `nginx -t`、再优雅 reload 的专属 hook；`certbot.timer` 已启用。该设置未改其他域名的续期参数。

站点仅开放预期 API/WebSocket 路径；内部 API、actuator、数据库/缓存等路径在反向代理前拒绝。访问日志使用 `$uri`，不记录 query；没有删除 Authorization 或绕过后端岗位鉴权。

## 实际验证

[HTTPS 检查 JSON](https-verification-20261115.json)保留逐项结果：

- 使用系统默认 CA 校验，域名与证书匹配，无证书跳过选项。
- 未登录访问任职接口：HTTP 200、业务码 401，未获取任职数据。
- 正常 B 登录会话经新 HTTPS 域名读取任职：业务码 0，回读隔离环境已有的 7 个总部/门店任职。
- `/actuator/health`、`/internal-api/...`、`/admin-api/internal-api/...` 均返回 404。
- HTTP 返回 308，Location 指向相同域名 HTTPS 并保留路径。
- API 域名根路径 404 是此 API 站点配置的预期行为，不是 Web 管理后台入口。
- 旧 `api.zszhj.com`、`api2.zszhj.com` 仍健康 UP，原站点配置文件摘要保持一致，未重启旧后端/Runtime。
- 使用无敏感数据的 query canary 发起实际请求，再检查 B access log，确认 query 未写入日志；见 [日志验证](access-log-verification-20261115.json)。
- B API 和 Provider 在本轮核查时均 active/running。进程运行不替代设计、预算、AI 任务全流程验收。

## 当前剩余阻塞：微信公众平台合法域名登记

服务器 HTTPS 可用不等于小程序网络门禁已放行。本轮通过已安装的微信官方 `miniprogram-automator` 启动独立诊断项目，以相同 AppID `wxe584f6fad7b54487`、`urlCheck=true` 发起真实请求，结果仍为：

```text
https://platform.zszhj.com/admin-api/platform/posts/mine
request:fail url not in domain list
```

详见 [微信开发者工具实际探针](wechat-legal-domain-probe-20261115.json)。此诊断项目仅发起无登录的读取请求，不加载业务登录模块，不注入 token，不修改原有账号缓存，不写业务单据。

下一步需在该小程序的微信公众平台“开发管理 → 开发设置 → 服务器域名”中登记 `https://platform.zszhj.com` 为 **request 合法域名**（填写 origin，不带 `/admin-api` 路径）；媒体下载/上传及 socket 域名按实际能力分别核对。保存后刷新开发者工具的域名信息、重新编译并复测。不得靠关闭 `urlCheck` 代替登记。

当前业务后端仍为 B 的 `pg,integration` 隔离联调实例。本次完成的是公网 HTTPS 入口接通，未宣布正式生产切换、真实微信登录通过或客户—设计—工程财务全链通过。

## 回退

本次只替换本任务拥有的 B 站点文件；启用前的 ACME-only 配置保存在：

`/opt/zhongshu-platform-b-integration/evidence/AUDIT-20261114-R02/ingress-before-https.conf`

如需撤回 HTTPS 反代，恢复此文件到 B 站点路径，执行 `nginx -t` 后优雅 reload。保留证书及 DNS 记录，避免误删由其他操作创建的资源；不修改旧 A 站点或应用进程。Git revert 仅撤回文档/配置副本，不能自动回退服务器配置或此前追加的测试任职。
