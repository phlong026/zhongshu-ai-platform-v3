# 当前主机的 B 部署候选

2026-11-14 只读核查：主机 `124.223.61.18` / `ins-4m1linvl` / `ap-shanghai-5`，8 vCPU、16 GiB、5 Mbps。
系统盘 `disk-731jrb7z`，数据盘 `disk-aqc9ak09`，各 40 GiB。这里只提供未启用的配置片段，不是可发布证明。

## 存储选择

| 方案 | 数据位置 | 备份位置 | 使用边界 |
| --- | --- | --- | --- |
| 短期试点 | 现有 `/mnt/datadisk0` 下固定 B 子目录 | 尚无合格 B 专用备份盘 | 未加密状态须如实签入 proof/attestation；仅可作为待正式受信批准的受限试点候选 |
| 正式容量草案 | 建议 100 GiB B 加密数据盘，仅保留为容量测算 | 建议 200 GiB B 加密备份盘，仅保留为容量测算 | 新盘、KMS、快照和其他付费能力全部暂停；KMS 费用与授权须另行确认 |

若未来另行授权新增盘，挂本机必须选择上海五区；独立云盘 ID 只证明逻辑卷不同，不代表底层物理故障域独占。当前没有下单、创建、格式化或挂载任何新盘，也未启用 KMS 或快照。
现有盘为未加密盘，没有快照或定期快照策略；这不是本轮代码硬拒绝条件，但 evidence、host proof 与 attestation 必须一致记录 `encrypted=false`，不得伪装成生产加密。当前顶层只有系统 `lost+found`，文件占用约 20 KiB，非 root 可用 37.058 GiB；本轮没有初始化任何数据目录。
试点留 12 GiB 运维余量后约有 25.06 GiB 业务头寸；该数值不是生产规模承诺。正式方案的数据余量先按 25 GiB 规划，备份余量 32 GiB，单次备份上限 96 GiB。

PG、WAL、两套 Redis AOF/RDB 与 Runtime journal 必须分别落在固定 B 子目录；`backup-staging` 不能当备份目标。
Docker 镜像、容器层、轮转日志仍在根盘 `/var/lib/docker`，本次未调整 Docker data-root。根盘当前非 root 可用 17.686 GiB；最终镜像 unique/shared 占用、导入空间及回滚版本须实测。可把导入后根盘至少留 8 GiB 作为初始运维讨论值，不能冒充现有代码门禁。

Runtime journal 当前会保留已完成记录，未发现自动清理策略。100 个最大图片任务可有约 2.81 GiB 在途预留，100 张最大图片的持久记录理论约 2.61 GiB，尚未计入 PG/Redis 增长。因此不能直接用 40 GiB 承诺 200 家组织、1000 员工、100 AI 突发容量。不得用每日业务限额代替容量治理；使用仍遵循公司积分与权限。

备份保留必须满足 `(保留份数 + 新备份/恢复所需份数) × 实测备份大小 + 余量 <= 实际可用容量`。
200 GiB 不是多份 96 GiB 上界全备的保留承诺。先做一份完整备份/恢复测量，再定保留份数；不自动删除已有备份。
另一块同机同 AZ 盘不能覆盖主机或可用区故障，正式灾备还应保留 B 专属异地副本/备份服务，不写入 A 桶，也不把业务资产桶版本控制称为独立备份。

## 身份模式与当前阻塞

默认模式仍是 `serial-wwn-thick-v1`：未显式配置时，`same_host_storage.py` 继续要求 root、Docker、A 与 B 卷的块设备祖先都有稳定 WWN/serial，并要求受信签名的 thick 供应证明。实际 guest 的 `vda` 没有 serial/WWN，因此默认模式仍 fail-closed。

只有显式设置 `B_SAME_HOST_STORAGE_IDENTITY_MODE=tencent-cbs-v1` 才启用腾讯 CBS 模式。该模式读取仓库外 root 所有、0600、最大 1 MiB 且采集完成不超过一小时的 `evidence.json`，并以 `B_SAME_HOST_TENCENT_CBS_EVIDENCE_SHA256` 绑定原始字节。采集器只调用 STS `GetCallerIdentity`、CVM `DescribeInstances` 和 CBS `DescribeDisks`；主机侧 IMDS 只辅助交叉核对 instance/region/zone，不替代云 API 身份。

仓内既有策略记录中的账号 UIN 候选是 `100045498012`，但仍必须由本次 fresh STS 响应确认，不能把旧策略文件当 CBS evidence。guest 中目标设备的稳定 serial 与 CBS `DiskId` 一致，是本项目用于绑定控制面和 guest 设备的审查策略，不是腾讯云对物理硬件、fault domain 或 thick provisioning 的官方保证。CBS proof/attestation 必须使用 `allocationContract=managed-block-v1`；不能把逻辑卷 ID、RequestId、UUID 或手写 JSON 称为物理独占或 thick 证明。

数据盘 proof 必须是 fresh v2 host proof，并由受信审批 attestation 绑定 `identityEvidenceSha256`、guest binding 与 `runtimeProofSha256`。云 evidence 过期后可以重新只读采集并重做 host proof/attestation；只要 Runtime 的稳定 `.capacity-proof.json` 内容和 SHA 未变，不需要重启 Runtime。真实信任锚、签名、host proof 和 attestation 当前均未提供。

配置片段只预填显式 identity mode 和已知主机/盘的注释；云 evidence 文件/哈希、proof 哈希、签名与信任锚故意留空，误用会拒绝。它们不能作为完整 deployment env 运行，也不能通过改后缀绕过门禁。现盘受限试点仍须正式受信方批准真实 `encrypted=false` 合同；当前没有 B 专用备份盘，完整备份与恢复仍被阻断；同一数据盘目录不能替代独立备份盘。
现有 `/etc/fstab` 使用 `/dev/vdb defaults,nofail`，以后可在备份与审阅后改为该盘 UUID；本次没有改动。

## 域名准备与切换

候选解析见 `dns-records.candidate.json`，站点与隔离测试见 [Nginx 候选](../nginx/README.md)。同账号 DNSPod 当前只有 `@`、`api`、`api2` 三条 A 记录，均保留。
两个新 B 域名在权威 DNS 查询时为 NXDOMAIN。旧证书 SAN 不覆盖它们，须独立签发和续期。
先准备独立 B 站点/证书与拒绝误路由的切换步骤，再生效新 DNS；不能先把新域名指向尚未识别 B Host 的 A 默认站点。

管理端保持 `VITE_BASE_URL` 为空、`VITE_API_URL=/admin-api`、`VITE_BASE_PATH=/`，通过 B admin 域同源代理；不要给 A API 增加 CORS。
小程序当前配置仍为 `https://api2.zszhj.com/admin-api`，正式 B 版本必须在独立服务就绪后改到 `https://platform-api.zszhj.com/admin-api`，并检查开发工具 storage 覆盖值，不能把现状称为 B 已独立联调。
首发沿用已验 B 原生 COS 对象域名；`b-assets` 自定义域需另行验证签名 Host、SDK 地址、Runtime 精确 origin 与微信合法域名，不能只做 CNAME 或放开通配 origin。

## 官方依据

- [DescribeDisks](https://cloud.tencent.com/document/product/362/16315)
- [DescribeInstances](https://cloud.tencent.com/document/product/213/15728)
- [GetCallerIdentity](https://cloud.tencent.com/document/product/1312/48195)
- [实例元数据](https://cloud.tencent.com/document/product/213/4934)
- [CBS API](https://cloud.tencent.com/document/product/362/15633)

官方字段由腾讯维护的 SDK 模型交叉核对；未找到 guest serial/WWN、thick 或物理故障域独立的公开保证。
历史控制台报价只作为容量讨论记录，不是采购建议或授权。新盘、KMS、快照/备份点及异地副本费用能力均暂停；恢复工作前必须取得新的明确授权和实时报价。
