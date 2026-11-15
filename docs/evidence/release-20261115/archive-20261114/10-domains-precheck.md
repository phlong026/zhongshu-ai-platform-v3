> 历史归档：以下内容记录截至 2026-11-14 的阶段状态，仅用于分支审查追溯，不代表 2026-11-15 集成候选的当前发布、部署或验收结论。

# 10-precheck · 合法域名静态预检（任务 10 前置）

- **执行**：主会话代理（静态枚举，2026-11-14）。**核对**：待管理员在 mp 后台逐项确认。
- **关联任务**：任务书 10；正式证据仍为 `10-domains-files.md`（真机验证后归档）。
- **方法**：全量扫描 `miniprogram/**.js`（排除 vendor）的 `wx.request / wx.uploadFile / wx.downloadFile` 调用点。

## 一、调用点枚举（当前候选 `0f60217b`）

| API | 调用点 | 目标 | 应配域名类别 |
| --- | --- | --- | --- |
| `wx.request` 主通道 | `api/http.js:123`（全量 API 封装） | `api2.zszhj.com` | **request** |
| `wx.request` COS PUT | `api/finance.js:132` | COS 桶预签名 URL | **request** |
| `wx.request` COS PUT | `api/franchise/engineering.js:181` | COS 桶预签名 URL | **request** |
| `wx.request` COS PUT | `pages/workflow/explore/platform-bridge.js:431` | COS 桶预签名 URL | **request** |
| `wx.request` dev 字节通道 | `api/finance.js:148`（`/platform/dev/...`） | `api2.zszhj.com` | request（同一域名；通道本身归任务 06/12A 处置） |
| `wx.uploadFile` | `api/http.js:186`（头像 → `/infra/file/upload`） | `api2.zszhj.com` | **uploadFile** |
| `wx.downloadFile` | `pages/build/record/index.js:41` | COS 桶预签名 URL | **downloadFile** |
| `wx.downloadFile` | `pages/workflow/commission/index.js:179` | COS 桶预签名 URL | **downloadFile** |
| `wx.downloadFile` | `pages/workflow/explore/platform-bridge.js:470` | COS 桶预签名 URL | **downloadFile** |

## 二、域名配置核对清单（管理员逐项打勾）

| 域名 | request | uploadFile | downloadFile | 说明 |
| --- | --- | --- | --- | --- |
| `https://api2.zszhj.com` | ☐ | ☐ | 不需要 | API 主通道 + 头像上传 |
| `https://zhongshu-design-1397776231.cos.ap-guangzhou.myqcloud.com` | ☐ | ☐ | ☐ | COS 附件直传/回读 |

## 三、🔴 关键风险（本次预检新发现）

COS 直传 PUT 走的是 **`wx.request`**（3 处）。历史记录（2026-11-12/13）只确认给 **uploadFile/downloadFile** 补过 COS 桶域名——**request 白名单是否含 COS 桶域名未经确认**。若缺失：真机所有附件直传（财务凭证/工程照片/委托图片）直接失败，模拟器与工具上传不复现（urlCheck 关闭）。

- [ ] 管理员在 mp 后台核对 **request 合法域名**是否包含 COS 桶域名；缺失则补录并截图留证
- [ ] 补录后在任务 10 做真机全链验证（票据→PUT→complete→下载回读）

## 四、边界

- 本清单为静态枚举+历史记录交叉，不替代 mp 后台实况与真机验证（urlCheck=false 会掩盖缺配）。
- dev 字节通道（`/platform/dev/`）的上线处置按任务书 06/12A 走，本清单仅登记其域名归属。
