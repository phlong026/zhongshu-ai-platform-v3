# 2026-11-16 登录及个人工作台部署记录

## 发布状态

- 按用户要求先部署 B 后端，再上传小程序。
- B 后端已部署，2026-11-16 01:41:35（北京时间）线上就绪检查 UP。
- 小程序 `wxe584f6fad7b54487` 已于 01:43 上传开发版本 `1.0.20261116.1`，官方 CLI 返回 `√ upload`。
- 本次预览码：`qa/login-release-20261116/phone-preview.png`。
- **真机验证待用户在手机输入密码、完成微信授权并反馈。尚未确认两种登录的真机结果，也未确认设为体验版、提交审核或正式发布。**

## 已验证

- 独立前端发布包完整 npm check 通过：272 个 TAP 用例及静态检查。
- 后端候选程序的真实 PostgreSQL 16.15、Redis 隔离集成回归：38/38 通过；微信凭据交换与令牌签发使用测试替身，不作为真机授权证据。临时测试容器已清理。
- 线上 GET `/platform/auth/login-policy` 返回 6–64 位密码规则及当前协议版本。
- 两条登录入口均拒绝新版缺失协议同意的请求，错误码 `1104000016`。检查使用与小程序一致的 `tenant-id: 1`。
- 线上搜索“裴火荣”：`personName=裴火荣`、`roleName=老板`、`shortOrgName=合家美宅 · 江西南昌高新区`。
- 官方预览和上传均通过，主包 1,840,022 字节，总包 6,940,020 字节。
- 01:46:47 只读核对：B 健康正常，微信 AppID 及密钥配置存在；用户 145 有 1 条有效微信绑定。其最新令牌创建时间仍为 01:19:01，早于本次上传，不能用来证明本次真机通过。

## 发布隔离

前端以 Git `6a485c994be1dd8ccdd731ecca3f6e16d52d7a88` 的已验收基础版本，叠加本次登录、个人资料、首页顺序、任务与学习修改，未打包工作区中并行开发的业务迁移、数字人等改动。依赖文件保持已固定的官方原始字节，避免 Git 换行归一化影响完整性校验。实际 725 个小程序文件的 SHA-256 见前端发布清单。

后端基于线上 `platform-055c0d00-mainline.jar`，仅编译替换 13 个源文件涉及的 22 个类，分属 org、authz、identity、franchise-content 四个模块。编译和回归均使用线上程序的运行依赖。校验其他类、资源及数据库迁移字节全部未变。源文件和变化类的哈希见 backend-candidate.json。

- 原程序 SHA-256：`cb826d16cd1bb397cfe01cea10e7213825cb751ace691a06a8303e4d08af2f3c`
- 新程序：`/opt/zhongshu-platform-b-integration/release/platform-login-20261116-414c13d4.jar`
- 新程序 SHA-256：`414c13d43b77dae61f8381fe9010df5906e11876b8adb2856659fda54455f2af`
- 仅重启 `zhongshu-platform-b-integration.service`。A、A runtime、B provider 的进程状态及受保护配置、A 程序哈希均确认未变。

## 备份及回退

成功发布对应的私有备份目录：`/opt/zhongshu-platform-b-integration/release/backup-login-20261116-174057`。包含原服务配置、状态指纹和 B 库自定义格式备份；备份 1,346,795 字节，已用 pg_restore 列表校验。未修改密码、绑定、业务数据或数据库结构。

本次通过 `/etc/systemd/system/zhongshu-platform-b-integration.service.d/90-login-20261116.conf` 切换程序，原服务文件及运行参数未变。回退时只移除此新增 drop-in，执行 daemon-reload 并重启上述 B 服务，核对 readiness；原程序仍保留。无须恢复数据库。

发布检查脚本曾因漏带租户请求头、以及错误使用数字账号标识（实际为 UUID），两次触发自动回退。修正后按真实客户端请求格式完成部署和冒烟检查；上述两次属于检查脚本错误，不代表登录回归失败。各次私有备份均保留。

## 真机验收待办

1. 扫描本次预览码，退出旧会话，用账号密码登录，核对姓名及岗位。
2. 退出后点击微信一键登录；已绑定账号应直接进入同一用户及岗位，不再要求账号密码。
3. 如尚未绑定，先明确绑定一次，再退出重试微信入口。
4. 接到用户反馈后，以时间及对应用户记录辅助核对；不在聊天中索取或记录密码、令牌或微信 openid。

## 证据

`qa/login-release-20261116/` 中保存 backend-deployment.json、backend-candidate.json、backend-results.json、backend-verified.log、frontend-check.log、frontend-source-manifest.json、frontend-release-manifest.json、live-account-display.json、phone-audit-baseline.json、preview.log、preview-info.json、upload.log、upload-info.json、release-status.json。

本地独立小程序发布工程保留在 `.tools/login-release-20261116/frontend`；后续重新预览或上传应使用该工程，避免把其他聊天的未验收修改一起发布。
