# B 独立设计能力部署与恢复边界

本目录只描述和校验 B。它不会停止、挂载、迁移或写入 A。`compose.yaml` 提供 B 主 API/PG/Redis，
`compose.capabilities.yaml` 增加 B Provider、Provider 专用 PG/Redis、一次性迁移容器和 B Runtime。

## 发布前输入

1. 在隔离构建环境生成并扫描三个不可变镜像：`PLATFORM_API_IMAGE`、`DESIGN_PROVIDER_IMAGE`、
   `AI_RUNTIME_IMAGE`。所有镜像（含 PG/Redis）只接受 `repository@sha256:<64 hex>`；版本 tag 即使不是 latest
   也会拒绝。生产 Compose 禁止 build/pull。
2. 将 `deployment.env.example` 复制到仓库和发布目录之外。所有凭据文件须为外部文件，Linux 上归专用
   GID 20000、不可 world-readable。
3. `PLATFORM_CONFIG_FILE` 必须包含真实的 `zhongshu.org-point.headquarters-tenant-id`、固定
   `zhongshu.design.remote.origin=https://design-provider:48443`、B API→B Provider 的独立调用身份和密钥。
   不得使用测试 tenant=1，不得复用 A 的消费方或 Runtime 密钥。API 的 active-key-id、caller 和 Base64 HMAC
   key 必须与 Provider 同 key-id 的 capability 配置完全一致。
4. `PF_DESIGN_PUBLIC_CASES_ORIGIN` 只指向 A 的匿名公开案例 HTTPS origin；B API 的设计调用固定为
   `https://design-provider:48443`。二者不能相同。
5. Provider TLS 证书 SAN 必须包含 `design-provider`，健康检查固定访问
   `https://design-provider:48443/actuator/health/readiness`，不能改用绕过 SAN 的 IP。API/Provider 使用的外部 truststore 必须在系统公共 CA
   基础上加入 Provider CA，不能用只含内部证书的空 truststore 替换公共根；Runtime 使用同一 Provider CA PEM。
   Compose 中 `changeit` 是只含公钥证书 truststore 的非秘密完整性密码合同；truststore 严禁包含私钥，生成时
   必须显式使用相同密码。Provider 私钥 keystore 密码仍只允许放在外部 Provider properties 中。
6. Runtime journal 必须是 Runtime 独占写入的 thick-provisioned Linux ext4/xfs 挂载点。普通 Compose
   named volume、NFS、thin-provision、压缩或去重卷不满足要求。卷管理员在根目录创建 0600、Runtime
   UID 所有的 `.capacity-proof.json` 并提供原始字节 SHA-256；还需保存真实 fsync p99 和容量证据。
7. `COS_B_BUCKET` 必须等于 Provider properties 中真实的 COS bucket；Provider
   `zhongshu.ai.internal-secret` 必须等于 Runtime `ZS_INTERNAL_SECRET`。校验器以常量时间比较并分别执行 HMAC
   challenge，只报告合同是否通过，绝不输出密钥。
8. Provider 存储必须为 `cos`，Runtime 必须为 `ZS_AI_STORAGE_MODE=https`，且
   `ZS_AI_STORAGE_ORIGINS` 必须包含 Provider 审核过的 COS HTTPS origin。`local`、`local-fs` 或其他 origin
   一律拒绝，避免产物落在备份 bucket 之外。

先做无副作用校验：

```bash
python3 deploy/platform/verify-capability-deployment.py \
  --env-file /absolute/external/deployment.env
docker compose --project-name zhongshu-platform-b \
  --env-file /absolute/external/deployment.env \
  -f deploy/platform/compose.yaml -f deploy/platform/compose.capabilities.yaml config --quiet
```

只有校验通过、旧共享源库在途设计任务和预扣已经只读核对、B 价格和总部权限已明确后，才可进入部署审批。
Provider 独立库还必须为每个启用的 B 公司建立经过核对的 `design_capability_organization` 映射，并配置
Provider 成本目录；不能把 A 的 tenant、service account、余额或价格表直接复制过来。

所选 env 文件是唯一部署输入。脚本会拒绝同名 shell 变量与文件值不一致，也拒绝所有 ambient `COMPOSE_*`；
不能靠 shell 临时覆盖候选 digest、路径、bucket 或边界配置。

## 强制迁移与启动顺序

先用既有 `ops.py migrate` 迁移 B 主库。完整 Compose 会启动两组 PG/Redis；每个候选版本只能通过以下发布工具
启动能力服务，不得手工 `compose up design-provider`：

```bash
python3 deploy/platform/release-capabilities.py \
  --env-file /absolute/external/deployment.env \
  --execute --confirm-target zhongshu-platform-b/capabilities
```

该工具强制三 writer 预先停止，核对本地候选 RepoDigest 与 Docker actual image ID，删除旧迁移容器，以同一候选
digest 重建并运行 `design-provider-migrate`，再次核对实际 image ID 和 `exited/0` 后，才依次重建 Runtime、
Provider、API。每个 writer 都再次核对 digest/actual image ID 和健康状态；失败时停止本轮已启动的 writer。
Provider 自身会在 eager singleton 初始化阶段、Web connector 可接受连接之前，用应用只读连接执行 Flyway
`validate`，并强制 pending/future migration 均为 0；旧 schema、
checksum 不匹配或候选迁移未执行时，即使绕过发布脚本直接 `compose up` 也会启动失败。它不会自动 migrate 或
修改历史迁移。Compose 命令行固定 `spring.main.lazy-initialization=false` 与 `spring.flyway.enabled=false`，启动
门禁 bean 同时显式 `@Lazy(false)`；外部 properties 即使写入 `lazy=true` 也不能跳过门禁。

Runtime 和 Provider 健康后，发布工具先执行三条无付费、无业务写入的真实网络 smoke：Runtime 经 Provider TLS SAN 使用共享
HMAC 调用 Provider `auth-smoke`；Provider 使用同一 HMAC 查询一个随机不存在的 Runtime receipt。任何 TLS、
CA、shared secret 或签名实现不一致都会令发布失败并停止本轮所有 attempted writer。Provider auth-smoke 不写
业务库或 replay key；随机不存在的 Runtime receipt 只读 journal。
随后用不发布端口的临时 API image 容器读取真实 platform properties，向 Provider 发送 capability HMAC smoke；
只有三条 smoke 均成功后才启动正式 API 并等待健康，避免 API 在校验完成前短暂接流量。
两个 Provider smoke endpoint 只存在于不发布主机端口的 B 私网，并且每次 Provider 启动只接受首个有效签名；
同一签名可在签名时效窗口内幂等重试，避免成功响应丢失导致发布误判，其他签名返回 404。探针和无效请求不会占用机会。
它们不返回业务数据、不写业务库，监控应告警任何发布窗口外的访问。
Runtime 先取得 journal 单写者锁；Provider 在 Runtime receipt 可用后启动；最后 B API 健康。服务之间只使用
`zhongshu-platform-b_private`。数据库、Provider 和 Runtime 均不发布主机端口，唯一主机入口仍是 B API 的
`127.0.0.1:48081`。

生产变更仍须由既有发布流程显式批准；本部署包不包含自动上线命令。

## 一致性备份

每次备份先停止新的 B 外部流量，再停止 `api`、`design-provider`、`ai-runtime`，并确认迁移容器已退出。
从三个写入服务全部停止开始，到 COS 版本清单和本地备份全部完成为止，必须保持同一个冻结窗口。同一冻结
窗口内创建以下快照，清单只记录 SHA-256、对象版本和时间，不记录任何凭据：

- B 主 PostgreSQL custom dump 和 B Redis RDB；
- Provider PostgreSQL custom dump 和 Provider Redis RDB；
- Runtime journal 文件树的只读快照，包含 `.capacity-proof.json`、writer lock 与全部 receipt/outbox；
- COS bucket/versioning 状态、对象版本清单和生命周期策略导出。COS 数据由云侧版本化/备份恢复，不能只备份数据库；
- 三个镜像 digest、两套 Flyway schema history、配置版本标识和冻结点。

备份目录必须在仓库/发布目录外，0700，离线复制后做恢复演练。密钥文件、properties、runtime env、TLS 私钥
和供应商凭据一律不得进入备份包。

经审批的冻结操作模板如下；它只操作项目名 `zhongshu-platform-b`，不得改名指向 A：

```bash
export B_ENV=/absolute/external/deployment.env
export B_COMPOSE='-f deploy/platform/compose.yaml -f deploy/platform/compose.capabilities.yaml'
docker compose --project-name zhongshu-platform-b --env-file "$B_ENV" $B_COMPOSE \
  stop --timeout 120 api design-provider ai-runtime
docker compose --project-name zhongshu-platform-b --env-file "$B_ENV" $B_COMPOSE \
  ps api design-provider ai-runtime postgres-b redis-b postgres-design redis-design
```

确认三个 writer 均为 exited、候选迁移容器为 exited/0、四个数据服务均为 healthy 后，仍在冻结窗口内用云侧
只读清单命令生成不含凭据的 COS 版本清单。`bucket` 必须精确等于 env 文件中的 `COS_B_BUCKET`，`generatedAt`
必须晚于最后一个 writer 的 FinishedAt 且不晚于本次备份检查时间。JSON 至少包含 bucket、生成时间、versioning
状态以及每个对象的 key/versionId/size/sha256；
空桶也必须显式使用 `objects: []`，例如：
`{"bucket":"...","generatedAt":"...","versioningEnabled":true,"objects":[{"key":"...","versionId":"...","size":123,"sha256":"<64 hex>"}]}`。
不得把 SecretId、SecretKey、临时 token、签名或预签名 URL 放入清单。随后执行：

```bash
python3 deploy/platform/full-backup.py backup \
  --env-file /absolute/external/deployment.env \
  --output /absolute/off-repo/backup-YYYYMMDDTHHMMSSZ \
  --cos-inventory /absolute/external/cos-version-inventory.json \
  --writes-frozen --confirm-target zhongshu-platform-b/full

python3 deploy/platform/full-backup.py verify \
  --env-file /absolute/external/deployment.env \
  --output /absolute/off-repo/backup-YYYYMMDDTHHMMSSZ \
  --confirm-sha256 <受信任的manifest.json SHA-256>
```

`full-backup.py` 只在三个 B 写入服务精确存在且 exited、候选迁移容器精确 exited/0、四个数据服务健康时工作；
快照前后会复核同一组容器 ID、实际 image ID 和冻结时间，任何变化都拒绝产出可信 manifest。它不停止服务、
不恢复数据，也不读取或打包任何密钥。这样可以避免脚本越权改变生产状态。

## 恢复

恢复只允许到全新的 B 资源，不能 `--clean`、不能覆盖 A，也不能把旧共享库或旧钱包余额合并进 B：

1. 保持外部入口、API、Provider、Runtime 全部停止；先执行上一节 `verify`，核验受信任 manifest SHA 和镜像 digest。
2. 创建全新的 B 主 PG/Redis 和 Provider PG/Redis。先用本版本初始化脚本创建四个独立角色和空库；保持
   API/Provider/Runtime 停止、两个新 PG 运行，将以下命令中的资源名替换为经审批的**新 B** 容器名；不得
   使用 A 容器/卷/数据库：

   ```bash
   docker cp platform-postgres.dump <new-b-postgres>:/tmp/platform.dump
   docker exec -u postgres <new-b-postgres> pg_restore --single-transaction --exit-on-error \
     --no-owner --no-privileges --role=platform_owner \
     --dbname=zhongshu_platform_b /tmp/platform.dump
   docker cp provider-postgres.dump <new-b-provider-postgres>:/tmp/provider.dump
   docker exec -u postgres <new-b-provider-postgres> pg_restore --single-transaction --exit-on-error \
     --no-owner --no-privileges --role=design_provider_owner \
     --dbname=zhongshu_design_provider /tmp/provider.dump
   docker exec -u postgres <new-b-postgres> rm -f /tmp/platform.dump
   docker exec -u postgres <new-b-provider-postgres> rm -f /tmp/provider.dump
   ```

   Redis 只允许恢复到从未启动过的全新空卷。保持对应 Redis 容器停止，按部署平台核实实际卷挂载点后，将
   `platform-redis.rdb` / `provider-redis.rdb` 分别放为各自新卷的 `dump.rdb`，owner 设为 999:999、mode 0600，
   再按原 ACL 启动。若部署平台无法证明卷为空或不能离线写入，则恢复门禁失败，禁止用运行中 `redis-cli`
   覆盖。启动后校验两套 Flyway history、积分 RESERVE/SETTLE/UNKNOWN、Provider event receipt 和输出 ticket。
3. 在全新的同类独占 thick ext4/xfs 卷上离线解包 Runtime journal：

   ```bash
   test -n "$NEW_B_JOURNAL" && test -d "$NEW_B_JOURNAL" && mountpoint -q "$NEW_B_JOURNAL"
   test -z "$(find "$NEW_B_JOURNAL" -mindepth 1 -maxdepth 1 -print -quit)"
   tar --extract --gzip --file runtime-journal.tar.gz --directory "$NEW_B_JOURNAL" \
     --same-owner --numeric-owner --no-overwrite-dir
   ```

   保持 owner/mode，删除旧 writer lock 前须人工
   证明旧实例已永久停止。重新生成并人工批准新的卷能力证明，不能沿用旧卷
   的 `volumeId`/hash。恢复后先只读核对 UNKNOWN/outbox，再允许 Runtime 启动。
4. 用云厂商的“指定 versionId 复制为当前版本”能力按 `cos-version-inventory.json` 逐项恢复到经审批的新 B bucket；
   禁止 restore 到 A bucket。重新导出清单并逐项比对 key/versionId/size/sha256；任何缺失、大小/摘要不符或授权不明
   的输出保持隔离，不自动结算或退款；恢复对象内容的 SHA-256 必须与冻结清单一致，ETag 不能替代 SHA-256。
   云侧命令必须由正式手册填入真实 region/bucket 后审批，本仓库不保存
   这些生产标识和凭据。
5. 先启动数据库和迁移，再 Runtime、Provider、API；仅在公司积分、receipt/outbox、对象引用和 A 未受影响
   的检查全部通过后恢复 B 流量。

Redis 恢复、COS 版本恢复和 journal 卷替换具有外部副作用，必须在服务器的正式恢复手册中使用实际卷/桶标识
并经过审批；本仓库不会猜测这些生产标识或自动执行破坏性恢复。

## 尚需现场验收

- 真实总部 tenant、五项 HQ 权限授予和真实价格；
- Provider TLS/COS/腾讯审核与真实 AI 供应商；
- Runtime 独占卷、thick 属性、空间与 fsync p99；
- Provider/Runtime/COS 的备份恢复演练；
- 冻结窗口内由可信云身份导出的 COS 版本清单；仓库脚本只能验证 bucket、时间、字段和摘要，不能证明云账号身份；
- A 文件、进程、schema、账本与正常设计链路在 B 部署前后均未被修改。

## 隔离恢复演练门槛

演练必须在 Linux 测试主机和独立 B 测试 bucket 完成，不能用 macOS 目录、Docker named volume 或 loop 文件冒充
生产 thick 卷：

1. 为测试主机分配可由存储平台证明为 thick 的独立块盘，格式化为 ext4/xfs，挂载为 Runtime 专用目录并生成
   `.capacity-proof.json`、SHA-256、容量和 fsync p99 证据。
2. 将三个测试镜像推入隔离 registry，使用 registry 返回的 `@sha256`，生成仅供测试的 Provider SAN 证书、外部
   secrets、真实非 1 的测试 HQ tenant；不得复制 A 凭据或连接 A 数据库/bucket。
3. 创建启用 versioning 的独立 B 测试 bucket，用受限只读云身份在 writer 全部 exited 后导出
   key/versionId/size/sha256 清单；将 bucket 精确写入 `COS_B_BUCKET`。
4. 运行 `verify-capability-deployment.py` 和 `release-capabilities.py`，确认迁移容器保留为候选 digest、actual image
   ID、exited/0；制造 PG、Redis、Journal、COS 测试数据后冻结 writer，运行 `full-backup.py backup` 与 `verify`。
5. 在第二组全新 B 资源按“恢复”章节导入，逐项核对双 Flyway history、积分状态、receipt/outbox、Journal 和
   COS SHA-256；最后证明演练期间不存在任何 A 网络、容器、卷、数据库或 bucket 访问。

任一物理卷证据、可信 COS 导出或恢复核对缺失，演练结论必须为 blocked，不能用结构校验或 mock 测试替代。
