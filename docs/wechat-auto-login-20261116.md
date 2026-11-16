# 微信手机号自动登录修复（2026-11-16）

## 当前结果

老板、门店负责人及员工采用同一流程：后台建立账号并填写手机号后，账号密码可独立登录。首次微信登录仅获取微信官方手机号授权，后端以授权手机号匹配现有启用且在岗的用户，自动建立微信凭据关联并签发该用户的令牌；不要求先用密码登录，不创建额外用户，不修改密码。以后微信登录直接识别现有有效关联。

登录页已移除“登录后绑定当前微信”“绑定已有账号”、重试绑定及其状态和接口调用。手机号授权失败、未匹配或重复时停留微信通道并显示错误，由管理员核对后台手机号；不会自动转到密码登录。新手机号授权每次携带新的 wx.login code；授权 code 不持久化。离页、切换通道和重复点击均有代次与忙碌保护。

后端自动关联复用已有短事务提交服务，锁定并复核账号、手机号和任职，避免识别后数据发生变化仍提交关联；同一账号并发首次登录只建立一次关联。旧版 `/wechat-bind` 后端接口保留兼容，新版客户端不再调用。

## 验证

- 登录行为 36 项通过，覆盖 boss、owner、业务、工程、财务；包括首次授权、后续直接登录、密码独立使用、拒绝授权、手机号不匹配或重复、迟到回调、离页、协议与密码显示。
- 基于已上传 `.7` 构建的完整前端检查通过：301 个 TAP 用例及静态检查。
- 基于现网运行依赖的后端真实 PostgreSQL 16.15 / Redis 回归 40/40 通过。其中贯通用例由后台创建老板和员工，再分别先微信手机号自动登录、后账号密码登录，确认用户相同、密码不被微信流程修改、无须手动绑定。微信换码与令牌签发使用测试替身，未将其作为物理手机微信授权证据。
- 并发自动登录验证同一用户的两次登录均成功、只产生一条有效关联；失效、停用、改号、重复手机号等已有用例保留。
- 官方 CLI 预览和上传成功，主包 1,845,796 字节，总包 7,443,869 字节。

## 部署与范围

B 后端已于北京时间 2026-11-16 12:24:11 部署完成，readiness 为 UP，线上微信服务入口已加载并返回预期白名单错误（校验请求不发起微信外呼）。

- 基线：`/opt/zhongshu-platform-b-integration/release/b-design-20261116-dc74574258e5.jar`
- 基线 SHA-256：`dc74574258e54fba606fc905f0f0303860c57aaa097619bfdb62bc94203d9473`
- 新包：`/opt/zhongshu-platform-b-integration/release/platform-wechat-auto-20261116-a5e36993.jar`
- 新包 SHA-256：`a5e36993292f440edad8291783e7ce3f9e684f9efe1e233eede23125697b407a`
- 仅替换 identity 模块的 `StaffWechatLoginServiceImpl.class`，所有其他类和资源字节不变，不含数据库迁移。
- B 库部署前已有私有备份并用 pg_restore 目录校验。备份位置、哈希、服务进程和完整发布回执见 `qa/wechat-auto-login-20261116/backend-deployment.json`。
- 仅重启 B 服务；A、A runtime、设计 provider 的进程状态、服务配置和原 B 覆盖配置均保持不变。
- 新增覆盖文件：`/etc/systemd/system/zhongshu-platform-b-integration.service.d/96-wechat-auto-login-20261116.conf`。回退只移除此文件，daemon-reload 并重启 B 服务，即恢复原设计及运营基线。不要删除已有 90、95 覆盖文件。

小程序已于 12:25:52 上传开发版本 **1.0.20261116.8**，AppID `wxe584f6fad7b54487`。以 `.tools/design-release-20261116/frontend-final`（已上传 `.7`）为基础，仅修改 auth.js、account/index.js、account/index.wxml、account/index.wxss 四个小程序文件；其余文件哈希完全相同，保留新版老板首页、课程及设计功能。独立发布工程在 `.tools/wechat-auto-login-20261116/frontend`。

## 真机边界

物理手机验证仍需用户扫描本次新预览码，在自己的手机完成账号密码输入、微信手机号授权并反馈结果。后台不能在用户首次授权之前凭手机号推算其微信 openid。

新预览码：`qa/wechat-auto-login-20261116/phone-preview.png`。应核对：老板与员工首次微信只出现手机号授权、没有绑定按钮；授权手机号与后台一致时进入正确账号；退出后微信直接登录；密码登录仍可用。不匹配或重复的手机号需管理员核对，不提供用户手动绑定入口。

**已上传开发版不等于体验版已切换或正式发布；本次未声称完成体验版切换、提审、正式发布或物理手机验收。** 请使用本次预览码，旧体验版可能仍显示旧绑定界面。

证据目录 `qa/wechat-auto-login-20261116/`：frontend-check.log、account-check.log、account-api-check.log、backend-results.json、backend-verified.log、candidate.json、backend-deployment.json、frontend-changes.json、release-status.json、preview.log、preview-info.json、upload.log、upload-info.json。
