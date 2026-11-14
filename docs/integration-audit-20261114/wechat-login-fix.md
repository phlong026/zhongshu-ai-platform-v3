# 微信一键登录修复执行记录

2026-11-14，用户已明确答复「允许本次最小修复及共享服务重启」。本次例外仅用于恢复登录；[独立 B 部署清单](../superpowers/plans/2026-11-14-b-independent-deployment.md)对 A 的长期保护边界保持原样。

## 根因和实际修改

生产 `PF_WX_STAFF_APPID` 与小程序 `wxe584f6fad7b54487` 匹配，但相应 Secret 被微信 `code2session` 和 `stable_token` 均拒绝（40125）。进程与 `/opt/zhongshu/env.merged` 的值一致，且未发现命令行、JSON、Java options 或外部 Spring 文件覆盖，因此不是文件更新后未加载。

本地主仓 `众墅之家AI赋能平台/平台程序/.env` 有同 AppID 的另一有效配置。候选以 SSH 加密标准输入传入服务器内存，通过 `stable_token(force_refresh=false)` 校验后，仅替换 `PF_WX_STAFF_APPSECRET` 一行。未重置密钥，未借用其他 AppID 凭据，未在命令行参数、日志或仓库保存 Secret/access_token。

原配置备份在服务器 `/opt/zhongshu/backups/wechat-login-20261114T094354Z`，目录0700，文件0600。修改使用同目录临时文件与原子替换，保留目标原 uid/gid/mode。原配置与当前文件去掉该目标行后逐字节相同；JAR SHA256 在重启前后保持不变。

## 生效与验收

- 只进行一次受控 `zhongshu.service` 重启；新 PID `3040254`，2026-11-14 17:44:06 CST 进入 active/running，自动重启计数0。
- `api.zszhj.com`、`api2.zszhj.com` 的 `/actuator/health` 均 HTTP200、UP，收尾时再次复核。
- 开发者工具微信一键登录、手机号授权、`/platform/posts/mine` 和老板工作台落地成功。登录接口 HTTP200、业务码0；长时联调遇到会话401后，再次用微信成功登录，确认修复持续生效。
- 未修改前端或后端业务代码、nginx、unit、JAR、数据库schema和其他小程序配置。后续三个测试岗位的追加是用户另行授权的业务配置，见[任职证据](test-posts.json)。

## 检查与回滚

变换函数先以合成配置验证：仅替换唯一目标键、保留其他行与行尾、拒绝缺失/重复键；服务器目标检查为普通文件且非符号链接。执行脚本经独立只读审查，无阻断问题。正式写入前再次校验候选，保留权限受限备份并检查并发修改；写后核对目标文件、服务终态、双域名健康和真实微信登录。

若需要撤销该配置修复，由具备服务器权限的人员恢复备份 `env.merged.original`，按同目录 `metadata.json` 恢复原 uid/gid/mode，再受控重启同一服务并核对双域名健康。原凭据本身无效，回退会恢复原登录故障。Git revert仅撤销报告，不能回退服务器配置。

[脱敏执行证据](wechat-login-fix.json)含前后整文件哈希、JAR哈希与服务状态；不包含凭据值。
