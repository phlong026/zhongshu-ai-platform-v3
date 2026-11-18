# B 独立部署入口

本目录仅服务 `zhongshu-platform-b`，不会授权修改 A、购买云盘、开通 KMS、创建快照或部署生产。当前存储
候选与阻塞见 [site-plan](site-plan/README.md)，腾讯云只读证据采集见
[TENCENT_STORAGE_CAPTURE.md](TENCENT_STORAGE_CAPTURE.md)，Runtime 稳定 Journal 证明见
[ai-runtime-b/README.md](../../ai-runtime-b/README.md)。

## 同机存储身份模式

- 默认 `serial-wwn-thick-v1` 保持不变。没有 `B_SAME_HOST_STORAGE_IDENTITY_MODE` 时绝不自动切换云模式；
  WWN/serial 或可信 thick 证明不足仍然 fail-closed。
- 腾讯云模式必须显式设置 `B_SAME_HOST_STORAGE_IDENTITY_MODE=tencent-cbs-v1`，并同时提供仓库外的
  `B_SAME_HOST_TENCENT_CBS_EVIDENCE_FILE` 与其原始字节 SHA-256。证据须 root 所有、0600、非软链、
  不超过 1 MiB，且 `collectedAt` 距校验时不超过一小时。
- evidence 由 B 只读 CAM 身份调用 `GetCallerIdentity`、`DescribeInstances`、`DescribeDisks` 生成；IMDS 的
  instance/region/zone 只作为主机侧辅助核对。目标 guest serial 与 CBS `DiskId` 相符是本项目的逻辑绑定
  策略，不是腾讯云对物理硬件、fault domain 或 thick provisioning 的保证。
- CBS host proof/attestation 使用 `allocationContract=managed-block-v1`。数据盘仍须真实测量 fsync、空间水位、
  guest 设备绑定和 A/root/Docker 保护范围；Runtime 仍须实际写入块预留并保持单写者。

云 evidence 是短时控制面快照；过期后重新采集，并重做绑定新 evidence SHA 的 host proof 与受信 attestation。
Runtime `.capacity-proof.json` v2 是稳定的卷身份文件，不包含短时 evidence SHA；只要该文件原始字节及
`ZS_AI_JOURNAL_FS_PROOF_SHA256` 不变，轮换 evidence 不要求重启 Runtime。独立审批的 data attestation 必须
绑定同一个 `runtimeProofSha256`，防止部署证明与 Runtime 实际读取的文件分叉。

当前 candidate.env 故意留空 evidence 文件/哈希、proof 哈希、签名和信任锚，因此不能通过发布门禁。现有
`disk-aqc9ak09` 未加密，可作为显式绑定 `encrypted=false` 且经正式受信批准的受限试点候选，不能称为已满足
生产加密要求。正式生产仍建议独立加密数据盘与加密备份盘，但新盘、KMS 费用、快照和备份服务都须另行确认
与授权；本轮没有开通或初始化。B 专用备份盘尚不存在，完整备份恢复仍为阻断项。

## 旧 API 首次升级与恢复

冻结 B 的写入、回调和任务，停止旧 API。记录旧容器实际镜像的仓库摘要。用同一份候选配置依次执行 `ops.py backup`、`migrate` 和 `start`，这三个操作均可传 `--previous-api-image 仓库@sha256:摘要`。只有摘要吻合且已停止的旧 API 可以缺少专用素材卷，其余挂载、网络、运行身份和数据库归属照常核验。

迁移失败且旧容器仍在时，先核实已提交的迁移是否允许旧版读取。确认兼容后，给 `rollback` 同时传相同摘要的 `--previous-api-image` 和 `--image` 以及 `--schema-compatible`。脚本启动原容器并等待健康检查。如需恢复备份，先保全故障数据库，按既有恢复流程准备空的 B 目标，给 `restore` 传同一旧摘要、备份目录和独立核实的 manifest 摘要。核对 Redis、数据库和原单后再开放写入。

## 历史模拟套餐积分

在冻结期以只读会话执行本目录的 `simulated-point-inventory.sql`。由财务按原始发放逐笔核对消费、正式充值、冻结与已终结的任务预扣，确认应转出的剩余测试积分。任何未终结的预扣都须先按原业务处理。迁移 990 创建核对表；991 遇到缺少凭证、流水笔数或余额变更、在途预扣会中止。迁移负责人依据财务签核，将原流水 ID、核准转出量、核对时的流水笔数和正式余额、具名审核人及凭证索引录入 `org_point_simulation_review` 后重跑。数据库保留原记录并追加对应转出流水。普通应用账号不能代录财务决定。
