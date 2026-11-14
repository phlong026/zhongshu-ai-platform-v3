# 赋能平台联合审查变更记录

> 参考图链路的最新源码和配置以[2026-11-15协议](../joint-review-20261115/参考图跨应用读取协议.md)为准；下文保留当时审查口径，真实B运行验收仍待部署。

日期：2026-11-14。当前修复只在新工作树 `joint-review-20261114`、分支 `fix/joint-release-split-20261114`；基线为 `platform-split` 的 `60e70cfb`。尚未部署或签署上线放行。完整报告保存在同级小程序仓库的隔离工作树 `docs/joint-review-20261114/`。

## API契约变更

- `POST /admin-api/platform/org/teams` 需要 `Idempotency-Key`：去除首尾空格后1至128字符。同组织、同用户、同键、同内容返回同一部门ID；同键不同内容冲突。跨组织、跨用户作用域独立。仍允许按现有业务创建同名部门，不新增名称唯一限制。Web客户端未知结果重试保留原键，改内容或已成功的新操作使用新键。
- 财务创建、退款/付款确认等写入端点需要去除首尾空格后1至80字符的幂等键，在持久化前拒绝缺键、空键和超长键。具体覆盖见 `FinanceController`；原有按状态幂等的作废操作不新增请求头要求。
- 短信发送和验证在Redis脚本中原子执行：同手机号并发仅一个发送许可；拒绝不消耗日次数；验证码一次消费。短信供应商失败保留60秒间隔，避免重试风暴。未引入按IP限制。
- B远程设计调用新增每进程资源保护：`zhongshu.design.remote.max-in-flight=16`、`zhongshu.design.remote.max-in-flight-per-org=4`，见 `deploy/platform/runtime.properties`。受限请求在命令持久化前返回业务码429，客户端保留原键稍后重试。100人同时发起时允许明确繁忙，不能据此认定100项全部受理或完成。

设计自传参考的真实链路为小程序 `/franchise/design/assets` → B远程能力 → A生产COS票据，使用预签名HTTPS URL原始字节PUT，成功后再complete。B内容资产相对 `/admin-api/franchise/assets/{id}/content?token=...` 是另一条契约，不能用它替代设计上传验收。本地A返回的 local:// 不具有split现成上传中继，隔离联调需配置COS或单独设计中继，不能伪称已通过。

## 发布与回滚

本轮无数据库结构迁移；Java、Web须按匹配版本发布，团队创建请求头缺失会被拒绝。恢复旧行为可回退本轮逻辑提交并重新构建对应制品；运行后的业务记录不会随代码回退而删除。代码回退不等同于数据库恢复，应保留既有备份和独立恢复演练。

容量、角色真机、真实短信/COS/AI、备份恢复和生产运行制品仍按 [容量与资源保护](容量与资源保护.md) 分项验收。测试用例、模拟接口、集成容器、生产渠道必须分别记录。

## 总部新增只读接口

- `GET /admin-api/platform/ops/wallet-summary`：`platform:wallet:query`，当前管理员tenant内全量 `walletCount`、`totalAvailablePoints` 与按可用点数降序的 `topWallets` 前10条；不受列表100条分页上限截断。
- `GET /admin-api/platform/ops/design-prices/active?pageNo=1&pageSize=10`：`platform:design:price:query`，仅 active=true，沿用PageResult。原全部价格版本列表不变。
- 总部首页每项单独校验权限、显示加载或错误并可重试；流水图只反映最近100条明细，标题显式标明样本范围，不能用作全量月报。

## 最终本地验证

Web290/290、typecheck/lint/build通过；后端40模块全量verify加末轮org/finance79项回归，最终账本1698（1677通过、21跳过、0失败/错误），真实Redis另3/3。最终JAR重打包后已实际扫描通过，发布边界8项与扫描器6项测试通过。细项及跳过原因见 [后端验证](后端最终验证.md) 与 [独立复核](最终独立代码复核.md)。源码已有7个逻辑提交，未push/部署；其他尚未验收的内容业务/真实渠道/容量门禁见总报告。

## 2026-11-14 手动发布首发契约

用户已确认首发为“手动发布后登记链接”，关闭定时入口。本平台不持有或操作第三方社交账号，`accountId`在此链路表示操作者填写的账号名称/标识，不能解释为已授权账号。

- `POST /admin-api/franchise/content/publications`：提供本人workId、title、publishText、accountId、platform及稳定Idempotency-Key。accountId去首尾空格后1至64字符，超长拒绝而非截断；platform仅douyin/channels。非空scheduledAt一律拒绝，发生在查询/幂等回放/写入前；省略、null或空值兼容手动记录。新记录为publishing，表示待登记链接，返回实际创建时间。
- `POST .../publications/{id}/verify`：操作者开始人工登记回执时，允许本人本组织的publishing或历史scheduled进入verify；不调用第三方平台或调度器。数据库CHECK要求非scheduled的scheduled_at为空，因此人工核验时将原值保存为versions.originalScheduledAt，再清空主列；后续版本更新保留该历史字段及冻结内容指纹。重复调用不重复迁移；已发布记录保留既有幂等语义。
- `POST .../publications/{id}/link`：沿用作品链接唯一性与记录归属校验。保存只表示登记了用户提供的链接，不声称已核实第三方平台发布事实。
- `GET .../publications`：沿用同组织可读范围，补充已存publishText、tags、authorUserId、createTime，供真实列表及详情显示。手动登记动作仍限定本人，后端为最终授权边界。

该范围更新不替代容量、真实渠道、角色真机和部署回滚门禁。本次后端代码提交为`a5cc6412`；专项12/12、内容64/64、29模块1122项（1104通过、18既有跳过、0失败错误），40模块重新打包及实际制品扫描通过。最新JAR SHA-256为`cbb702707ebb2a5f0bdb18448f49bb34016af239752eb37cde2140b50239779c`。详见[本次后端验证](手动发布后端验证.md)、[独立验收](手动发布独立验收.md)及小程序隔离树13报告；上文1698账本属于首轮冻结证据，不代表本次全平台重跑。
