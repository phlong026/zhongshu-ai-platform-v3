# 当前主机的 B 部署候选

2026-11-14 只读核查：主机 `124.223.61.18` / `ins-4m1linvl` / `ap-shanghai-5`，8 vCPU、16 GiB、5 Mbps。
系统盘 `disk-731jrb7z`，数据盘 `disk-aqc9ak09`，各 40 GiB。这里只提供未启用的配置片段，不是可发布证明。

## 存储选择

| 方案 | 数据位置 | 备份位置 | 使用边界 |
| --- | --- | --- | --- |
| 短期试点 | 现有 `/mnt/datadisk0` 下固定 B 子目录 | 另购 B 专用备份盘 | 需先通过存储门禁；建议使用合成数据或经批准的受控测试数据 |
| 正式生产建议 | 新增 100 GiB 加密 B 数据盘，挂 `/mnt/zhongshu-platform-b-data` | 新增 200 GiB 加密 B 备份盘，挂 `/mnt/zhongshu-platform-b-backup` | 起始规划规格，须按可售盘型、增长、恢复空间与实时报价校准 |

新增盘挂本机必须选择上海五区；独立云盘 ID 只证明逻辑卷不同，不代表底层物理故障域独占。没有下单、创建、格式化或挂载任何新盘。
现有盘为未加密盘，没有快照或定期快照策略，当前顶层只有系统 `lost+found`，文件占用约 20 KiB，非 root 可用 37.058 GiB。
试点留 12 GiB 运维余量后约有 25.06 GiB 业务头寸；该数值不是生产规模承诺。正式方案的数据余量先按 25 GiB 规划，备份余量 32 GiB，单次备份上限 96 GiB。

PG、WAL、两套 Redis AOF/RDB 与 Runtime journal 必须分别落在固定 B 子目录；`backup-staging` 不能当备份目标。
Docker 镜像、容器层、轮转日志仍在根盘 `/var/lib/docker`，本次未调整 Docker data-root。根盘当前非 root 可用 17.686 GiB；最终镜像 unique/shared 占用、导入空间及回滚版本须实测。可把导入后根盘至少留 8 GiB 作为初始运维讨论值，不能冒充现有代码门禁。

Runtime journal 当前会保留已完成记录，未发现自动清理策略。100 个最大图片任务可有约 2.81 GiB 在途预留，100 张最大图片的持久记录理论约 2.61 GiB，尚未计入 PG/Redis 增长。因此不能直接用 40 GiB 承诺 200 家组织、1000 员工、100 AI 突发容量。不得用每日业务限额代替容量治理；使用仍遵循公司积分与权限。

备份保留必须满足 `(保留份数 + 新备份/恢复所需份数) × 实测备份大小 + 余量 <= 实际可用容量`。
200 GiB 不是多份 96 GiB 上界全备的保留承诺。先做一份完整备份/恢复测量，再定保留份数；不自动删除已有备份。
另一块同机同 AZ 盘不能覆盖主机或可用区故障，正式灾备还应保留 B 专属异地副本/备份服务，不写入 A 桶，也不把业务资产桶版本控制称为独立备份。

## 当前校验阻塞及整改方向

现有 `same_host_storage.py` 要求 root、Docker、A 与 B 卷的块设备祖先都有稳定 WWN/serial，并要求受信签名的 `thick` 供应证明。
实际 guest 的 `vda` 没有 serial/WWN，故现在不能运行 `prepare`。腾讯云控制面已确认系统盘与数据盘 ID，但没有给出物理 fault domain 或 thick 的证明；不能把 UUID、设备名或手写 serial 当成该证明。

后续应单独实现并审查腾讯云控制面身份校验：受限只读身份查询 `DescribeDisks`、`DescribeInstances`，绑定账号、实例、可用区、云盘 ID、用途、挂载状态、加密状态、容量、查询时间和 RequestId，再与 guest UUID、major:minor、挂载点交叉核对。
证据哈希由独立受信审批方签名；API RequestId 不是审批签名。新增云盘身份模式必须明确只证明逻辑卷隔离，保留旧模式，不把缺失证明静默放行。
当前代码未实现该兼容模式、真实信任锚未安装，也没有伪造 proof/attestation。

配置片段故意留空真实 SHA、签名等字段，误用会拒绝；它们不能作为完整 deployment env 运行，也不能通过改后缀绕过门禁。
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
- [DescribeSnapshots](https://cloud.tencent.com/document/product/362/15647)
- [CBS API](https://cloud.tencent.com/document/product/362/15633)

官方字段由腾讯维护的 SDK 模型交叉核对；未找到 guest serial/WWN、thick 或物理故障域独立的公开保证。
控制台已查到上海五区单块 100 GiB 增强型 SSD 一年基础价 996 元、单块 200 GiB 高性能盘一年基础价 697.20 元，合计 1693.20 元；数量各 1、备份点 0、暂不挂载、未选加密，未提交订单。
此报价不含 KMS、快照/备份点及异地副本费用。控制台提示加密需要先开通 KMS，且增强型 SSD 性能承诺需核实目标实例兼容性；不能把此基础价称为已确认的加密生产方案总价。最终以下单前实时配置与报价为准。
