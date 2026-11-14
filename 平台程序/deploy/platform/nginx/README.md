# B 域名 Nginx 候选配置

本目录只有待审候选文件，不是自动部署包。文件使用 `.conf.candidate` 后缀，不能直接被常见的
`/etc/nginx/conf.d/*.conf` include 命中；本目录也不提供复制、软链、reload 或证书签发脚本。

## 域名与边界

- `platform-api.zszhj.com`：仅将 `/admin-api`、`/app-api` 原路径代理到 B 的
  `127.0.0.1:48081`。小程序当前同时使用这两个前缀，切换时其 API 基址应改为
  `https://platform-api.zszhj.com/admin-api`。
- `platform-admin.zszhj.com`：从 `/srv/zhongshu-platform-b/web/current` 提供管理端静态文件，
  `/admin-api` 同源代理到 B；前端 history 路由回退到 `index.html`，缺失的 `/assets/*` 和常见
  静态文件扩展名返回 404，不回退到 SPA。
- 两个 B 域名都只对精确路径 `/infra/ws` 保留现有 WebSocket 功能；Upgrade/Connection 由 Nginx
  显式转发且边缘 access log 关闭，避免查询参数中的登录 token 写入 Nginx 日志。`/infra/ws/extra`
  等派生路径不代理、不升级。
- B 虚拟主机使用各自专属的脱敏 access log，只记录 `$uri`，不记录 `$args`、`$request`、Referer 或
  User-Agent；上游状态保留在 `upstream_status`。B 请求级 error log 指向 `/dev/null`（`crit`），包括
  普通上游失败在内均由脱敏 access log 观测，避免 error log 在任何级别附带含 token 的原始请求上下文。
  该策略不修改 A 或 Nginx 全局日志格式。
- 直连模式下 `X-Forwarded-For` 固定覆盖为 `$remote_addr`，不拼接客户端自报值。将来若接入 CDN，必须
  另行审核并配置精确可信代理网段和 real-IP 递归边界，不能直接信任公网传入的 XFF。
- 唯一业务 upstream 是 `127.0.0.1:48081`。Provider、Actuator、Redis、PostgreSQL、COS 管理面
  均不经这些候选入口暴露。
- `api.zszhj.com`、`api2.zszhj.com` 属于 A，不得复用、迁移或写入本候选。历史 Nginx 模板已移到
  `deploy/archive/nginx-api2.conf.disabled`，禁止部署。旧 `deploy/env.prod.template` 同样会复用 A 的
  数据库、Redis、COS 与身份，已移到 `deploy/archive/env.prod.template.disabled`；B 只能从
  `deploy/platform/deployment.env.example` 准备新配置。

候选配置不改写 API 路径、不启用代理缓存、不设置业务每日次数或调用限额。HTTP 80 只允许固定
ACME webroot `/var/lib/zhongshu-platform-b/acme` 的 challenge，其余请求跳转到对应固定 HTTPS 域名。该目录
必须由 B 独占，不得指向或软链到 A 的 ACME webroot。HTTPS 仅启用
TLS 1.2/1.3，且没有 `default_server`。

## 上线前必须完成

1. DNS：分别创建并核验两个 B 域名的解析，不修改 A 的 `api`/`api2` 记录。
2. TLS：分别签发 B 的独立证书 lineage：
   `/etc/letsencrypt/live/platform-api.zszhj.com/` 与
   `/etc/letsencrypt/live/platform-admin.zszhj.com/`。不得复用、复制或引用 A 的证书或私钥。
3. 管理端：将审核过的构建产物以原子方式切换到 `/srv/zhongshu-platform-b/web/current`，确认
   `VITE_BASE_PATH=/`、API 为同源 `/admin-api`。
4. 小程序：将请求基址切到 `platform-api.zszhj.com`，在微信公众平台登记该 HTTPS request/upload/
   download 合法域名；首发还必须登记并真机验收 B 桶的原生 HTTPS 对象域名
   `zhongshu-platform-b-assets-1397776231.cos.ap-shanghai.myqcloud.com`，配置未生效前不得切流。
   当前跨仓实现会在 `miniprogram/api/http.js:118-124` 将 base 与业务 path 直接拼接；默认
   `miniprogram/config/env.js:14` 的 base 必须保留 `/admin-api`。钱包在
   `miniprogram/api/wallet.js:20-24` 基于该值替换为 `/app-api`。上线前同时检查 storage 中的
   `zs-api-base=https://platform-api.zszhj.com/admin-api` 与
   `zs-wallet-base=https://platform-api.zszhj.com/app-api`，不得误配为仅 origin。
5. COS：首发使用已经真实验收的上述 B 原生 HTTPS 对象域名，自定义域名不是首发硬门禁。`b-assets`
   仅是后续候选；启用前必须让 API/Provider SDK 按该 Host 生成和验证签名，并将 Runtime 的允许 origin
   精确切换到同一 HTTPS origin，再重跑上传、下载、签名与真机验收。不得只加 DNS CNAME，也不得用通配
   或同时放宽原生域名与候选域名。完成整套切换前继续使用原生对象域名。
6. 防火墙与监听：确认 `48081` 只监听本机，Provider/数据库/Redis/管理端口未对公网开放。
7. 变更前用实际服务器 Nginx 完整配置执行 `nginx -t`，人工审阅 diff、准备回滚并走变更窗口。

当前状态：两个 B 业务域名的 DNS/SSL、小程序 API 与 B 原生对象域名合法域名仍为待生效门禁；COS custom
domain 是后续可选变更，不阻塞首发。**不得据此文档执行 Nginx reload**。

## 本地隔离检查

`verify-candidates.sh` 只使用本机已缓存的 Nginx 镜像、临时自签证书、临时静态文件、B upstream
fixture 和 A 域名 fixture。它执行 `nginx -t`、真实 HTTP 101 Upgrade 与 Host/路径行为检查，结束时
删除容器和临时目录，
不会拉取镜像、连接服务器或修改 DNS/云配置：

```bash
./deploy/platform/nginx/verify-candidates.sh
```

默认要求缓存镜像 `nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236`；
可通过 `NGINX_TEST_IMAGE` 指定另一个已缓存的等价镜像（脚本仍使用 `--pull never`）。该检查不是
服务器最终配置、证书链、DNS 或真实流量验收。
