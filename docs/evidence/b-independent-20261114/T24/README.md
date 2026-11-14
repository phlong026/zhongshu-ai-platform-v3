# B 供应商配置复用、一人多岗位与服务连通核验

当前 B 使用独立 API、Provider、Runtime、数据库、Redis、COS 和任务 journal。供应商连接参数从 A 正在运行的 Runtime 进程只读取得，已安全复制至 B 的 root 私有配置；没有复制 A 内部签名、COS 配置、任务路径或每日业务限额，没有修改或重启 A。

## 已完成

- 同一个用户、同一手机号在联调公司 org119 配置老板、业务、财务、工程四个岗位；不创建第二个用户。财务查询允许财务岗位访问、工程岗位访问被拒绝；工程项目查询允许工程岗位，组织人员管理仍拒绝。
- B Runtime 的独立镜像已运行，监听回环 `18082`；B Provider 使用独立 TLS，仅监听回环 `48443`。容器通过 B 私有 Docker 网桥的专用 TCP 转发访问 Provider，没有改 A 的 `8081`、Nginx 或公网入口。
- Runtime→Provider 签名探针返回 204，Provider→Runtime 只读回执探针返回 200。单次签名探针有一次性门禁，重复以新签名访问会返回404；最终探针在正常 B 配置更新重启后验证成功，未为探针重启 A。
- 供应商 `/models`：无认证 401、使用 B 复用的密钥 200；文字模型 `gpt-5.6-sol`、图片模型 `gpt-image-2` 均存在。只做鉴权及模型查询，没有发起付费生成。
- B 预算规则查询返回 code=0；平面、立面各创建一个独立免费项目，API→B Provider 的 TLS/HMAC 和数据库链路成功。测试预算规则仍为明确的 NON_PRODUCTION `LOCAL_IT / FRAME / STANDARD`，不能用作正式业务报价。
- A API、A Runtime、B API、B Runtime 健康检查均为 200/UP；A 的 JAR、两份 Nginx 配置校验值和服务启动时间与此前一致。

## 尚未执行

联调公司积分池 balance=0。真实平面任务提交被正常拦截，返回 `1072000011 公司积分池余额不足`，余额和 inFlight 仍为0，没有绕过扣分保护。

因此，预算规则和服务连通已验证，实际预算估算、润色及图片生成尚未实测成功。测试积分初值正在向用户确认；没有直接改余额、虚构支付或伪造第二个人复核。一人多岗位适用于岗位权限联调，不能替代正式流程中的两个不同用户。

未新增云盘、KMS、快照、付费服务项目。后续真实模型调用将使用同一供应商账号的现有额度，不能宣称供应商额度与 A 隔离。B 公司积分账本及记录始终独立。

## 主要证据

- `org119-multi-post-verification.json`：一人四岗位、权限隔离。
- `supplier-config-reuse-metadata.json`：脱敏供应商复用范围，未保存密钥。
- `supplier-readonly-probe.json`：供应商真实鉴权和模型列表。
- `connected-services-verification.json`：双向签名、健康及 A 保护对照。
- `live-provider-free-probes.json`：预算规则、免费项目及零余额池。
- `zero-balance-guard.json`：余额不足拦截，无冻结积分。

配置与服务的安装已完成；这仍是隔离联调环境，不冒充已通过正式存储签名审批或公网真机发布。
