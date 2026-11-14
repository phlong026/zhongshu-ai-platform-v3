# T21 服务器资源与 UNKNOWN 预留只读核查

日期：2026-11-14。采样窗口始于 16:40 CST。本文件补充 T01/T03，只记录服务、资源和数据库脱敏聚合，不记录用户 ID、账号标识、订单号、任务号、回执值、个人资料、配置值、凭据或令牌。

## 1. 核查方法与边界

- 使用既有授权通道执行严格主机密钥校验、非交互只读检查；未读取或记录 SSH 私钥、应用环境变量、进程环境、数据库密码、Redis 密码、支付/COS/TLS/HMAC 配置值。
- PostgreSQL 通过本机 peer 通道执行，固定 `default_transaction_read_only=on`、`REPEATABLE READ READ ONLY`、`statement_timeout=8s`、`lock_timeout=1s`，所有事务以 `ROLLBACK` 结束；仅输出结构是否存在和计数、金额/点数、状态聚合。
- 未重启、停止或重载服务，未部署 B，未修改业务数据、配置、网络、Docker、文件权限、磁盘或挂载。健康检查第一轮仅为统计响应字节临时写入 `/tmp` 后立即删除；未触碰业务或配置文件，后续请求均使用管道且不保存响应正文。
- 本文件中的“未发现”只覆盖本次列明的 systemd、监听、Docker、有限部署目录、nginx 启用站点和 PostgreSQL public schema，不推断其他主机、未扫描路径或外部系统。

## 2. 当前部署与 A 保护事实

| 对象 | 脱敏实测 |
| --- | --- |
| A 主服务 | `zhongshu.service` active/running，工作目录 `/opt/zhongshu`，监听 48080 |
| A Runtime | `zhongshu-runtime.service` active/running，工作目录 `/opt/zhongshu/ai-runtime`，监听 8081 |
| 旧第二服务 | `zhongshu-v2.service` inactive/dead，48081 未监听 |
| B 独立服务 | 未发现 `zhongshu-platform-b.service` 或 `zhongshu-design-provider.service`；48081、48443 均无 B 监听 |
| Docker | 无镜像、容器、local volume、build cache；未发现 B 网络或卷 |
| B 部署目录 | `/opt/zhongshu-platform-b`、`/opt/zhongshu-design-provider`、`/srv/zhongshu-platform-b` 均不存在 |
| nginx | 当前启用站点仍将 api/api2 的业务入口反代至 A 的 48080，未发现 B 独立入口 |
| A JAR | `/opt/zhongshu/yudao-server.jar`，186184621 字节，SHA-256 `a5e05c5b1b6317b3022c3c3057f45696a0222c0e9f9d532aa26609ae57401e91`；与 T01/11 月 10 日基线一致 |
| 健康 | A actuator HTTP 200；A Runtime health HTTP 200；readiness 子路径 HTTP 404，因此不能把该子路径记为已验收 |
| 公开案例 | 匿名公开案例列表只读请求返回业务 `code=0`、一行结果且存在下一页游标；未保存或输出案例内容 |
| 数据库 | 非模板库仅 `postgres`、`zhongshu_design`；未发现 B 独立业务库 |

结论：服务器当前仍只有 A 的运行证据，B 尚未部署。上述请求证明 A 在采样时可响应，不替代真实登录、设计、预算、支付或并发验收。

## 3. 资金、任务与 24 点预留追溯

### 3.1 总体聚合

| 项目 | 脱敏实测 |
| --- | --- |
| `fr-service:` 候选账号 | 账号、钱包、充值、AI 任务均为 0；该前缀不是旧 B 归属全集 |
| 全库钱包 | 104 个；available_points 合计 14337；reserved_points 合计 24 |
| 有预留的钱包 | 5 个 ACTIVE 账号、合计 24 点；其 `external_key` 均为空，不输出账号或用户标识 |
| 充值 | 3 笔，均 `SUCCEEDED + CREDITED`，amount_cents 合计 20000；未决支付和已付未到账均为 0 |
| AI 任务 | 236 个：SUCCEEDED 167、PARTIALLY_SUCCEEDED 8、FAILED 51、CANCELLED 10；所查在途状态和 UNKNOWN 任务均为 0 |
| AI attempt | 2 条 `finished_at IS NULL`，关联任务均已 CANCELLED；其中 1 条关联下述 UNKNOWN prompt，另 1 条未关联 |
| 其他在途面 | `fr_content_task` 总量/在途 0；支付通知 inbox、退款聚合无记录；outbox 475 条均 DISPATCHED |

### 3.2 24 点来源闭合

只读聚合得到以下一一对应关系：

1. `service_usage_charge` 有 9 条 `product=AI_PROMPT`、`state=UNKNOWN` 记录，分布在上述 5 个钱包，`point_cost` 合计恰为 24。
2. 按账号分组后，5 个钱包的 `reserved_points` 均与各自 UNKNOWN `point_cost` 合计完全相等；无无法匹配的钱包。
3. 9 条记录均已有 `call_receipt_id`，均无 `call_response_hash`。这里只记录“是否存在”，不读取或输出回执值。
4. 关联任务终态为 FAILED 8 条/22 点、CANCELLED 1 条/2 点；记录时间范围为 2026-10-07 至 2026-10-26。
5. 点数服务的 `reserve/release` 原语不写业务流水，因此不能仅凭最新 `design_point_ledger` 行判断预留来源；`service_usage_charge` 的 UNKNOWN 聚合才与 24 点完成数值闭合。

这 24 点不是当前运行中的 AI job，但表示供应商调用结果仍未知。上线前必须由唯一责任方依据供应商事实完成对账；在确认前不得释放、清零、重复扣费或迁入 B。

### 3.3 归属边界

- 5 个账号均没有 `fr-service:` 标识，但“无 external_key”不能证明属于 A，也不能证明属于 B。
- 线上未发现 `design_capability_organization`、service-account mapping 一类可用于确认 B 归属的表。
- 因此 24 点及 9 条 UNKNOWN 的应用归属标记为 **UNKNOWN**。它们不能被用作“旧 B 资金为零”的完成证据，也不能默认并入 B 期初余额。

## 4. 旧 B 远端命令证据

- 线上不存在 `fr_design_remote_command`，Flyway 中也没有 `20261111.925`；本地源码中的新远端命令状态机不是当前线上 schema，不能虚构其 REQUESTED/SENT/UNKNOWN 统计。
- 线上只有 `pf_command_event` 只追加审计表：franchise 域共 8 条，涉及 1 个组织，时间范围为 2026-11-08 至 2026-11-09。该表没有 state/lease 字段，不能用于证明远端命令已排空。
- 这些审计行证明旧赋能侧存在历史业务事实；“新远端命令表不存在”不等于旧 B 历史数据或写入方不存在。

## 5. CPU、内存与磁盘

| 资源 | 脱敏实测 |
| --- | --- |
| CPU | 8 vCPU；采样时 load average 0.19 / 0.15 / 0.10 |
| 内存 | 15 GiB，总 available 约 12 GiB；Swap 2 GiB，基本未使用 |
| A 当前内存 | Java 约 1.78 GB；A Runtime 约 0.43 GB；PostgreSQL 约 0.39 GB；这是瞬时值，不是容量承诺 |
| 根盘 | `/dev/vda3`，ext4，约 39 GB，总空闲 18.99 GB，使用率 53%，inode 使用率 6% |
| Docker | 29.1.3；Compose 2.40.3；Docker root 为 `/var/lib/docker`，位于根盘；当前 Docker 对象占用为 0 |
| 数据盘候选 | `/dev/vdb`，40 GB ext4，已挂载 `/mnt/datadisk0`，空闲约 39.79 GB；不是未挂载空盘 |
| 数据盘内容边界 | 顶层只读类型聚合仅见 1 个目录，未读取或记录目录名称、文件名或内容；本次没有发现当前 Docker 对象，A systemd 工作目录均在 `/opt/zhongshu`，但未据此推断数据盘所有权或可清理性 |

当前 Compose 中两个 PostgreSQL 和两个 Redis 使用 named volume，默认仍落在根盘 Docker root；只有 `AI_RUNTIME_JOURNAL_DIR` 是显式 bind 目录。即使将 Runtime journal 指向 `/mnt/datadisk0`，也不能据此证明其余 B 数据卷已迁移到数据盘。

本次没有执行全进程文件描述符、namespace 或后台任务的全量扫描；“未发现活动服务关联”仅指已列出的 Docker 对象为空且已查 A systemd 工作目录不在该挂载点，不能据此证明没有其他进程读取 `/mnt/datadisk0`。因此现有目录和文件必须完整保留。

### 容量结论

当前没有“同机部署 B 已安全足额”的证据：

- 根盘 18.99 GB 还需容纳三个不可变镜像、两个 PG、两个 Redis、日志和 Docker 元数据。
- 数据盘虽近空，但尚未配置为 B 专属卷，所有权/权限也未准备；现有数据必须原样保留。
- 未取得云平台对 `/dev/vdb` 为 thick-provisioned、非 thin/压缩/去重盘的证明，未做 fsync p99、峰值 I/O、镜像体积、生产数据增长、临时 dump 和离线备份容量测算。
- Compose 稳态资源上限约 6 vCPU、6.25 GiB；当前空闲和低负载只能作为候选依据，不能代替 A+B 隔离容量与故障恢复演练。

上线前应明确 B 的 PG/Redis/Runtime journal 各自落盘方式，提供数据盘供应属性、容量模型和 fsync 证据，并把备份放在运行卷之外。不得通过格式化、清理或移动 `/mnt/datadisk0` 现有内容来制造空间。

## 6. 仍缺失的上线证据

- 9 条 UNKNOWN/24 点的供应商事实、唯一对账 owner 和最终结算决定。
- 经审定的 `B_ORG`、`B_LEGACY_ACCOUNT`、`B_MONEY`、`B_WORK` scope；不能只依赖账号前缀。
- 可在 A 持续运行时使用并经生产验证的旧 B DRAINING/FROZEN 门禁、全部实例回执、任务/通知水位和唯一消费者。
- B 独立镜像 digest、数据库/Redis 账号与负向权限、外部配置和密钥文件权限。
- 与 A 分离且开启 versioning 的 B COS bucket、最小 CAM 策略和真实 origin；本次未访问任何 COS 账号或凭据。
- `/dev/vdb` 云平台供应属性、B 专属目录/卷设计、容量与恢复演练。

在这些证据完成前，本次结果仅是部署前只读基线，不构成 B 可上线、旧 B 已排空或 A+B 同机容量已验收。

## 7. 关联证据

- `docs/evidence/b-independent-20261114/T01/基线.md`
- `docs/evidence/b-independent-20261114/T03/旧业务只读核查.md`

B 独立工作树中的拆分方案、数据归属清单、部署手册和迁移校验器参与了边界核对，但不复制到本证据目录，也不写入不属于当前主工作区的失效相对链接。其中迁移校验器当前只提供离线 plan/check 与合成 fixture，不是生产导出、停写或迁移工具；历史结论也不能覆盖本次采样时间之后的状态变化。
