# 众墅 AI 赋能平台 Runtime B 1.0.0

本目录是 B（AI 赋能平台）的独立 Node 24 服务，不修改、不引用 A（AI 设计小程序）的运行目录。代码基线复制自 A 的 `ai-runtime`，保留其安全与设计生成能力；B 单独构建、配置、日志和部署。生产 Runtime 无第三方 npm 依赖；`sharp` 仅用于测试造图。真实调用尚未验收，不得把离线样本回写当作真实生成成功。

B 不设置任何每日业务调用次数上限。是否可用由 B 后端在领取任务及资金钩子处按公司权限和公司积分判断；Runtime 仍保留原子 Journal、付费结果 UNKNOWN 防重、HMAC 签名和补充需求润色的短时并发保护。不得用 Runtime 的“无日限额”绕过 B 后端积分校验。

## 处理合同

1. 使用 HMAC-SHA256 内部签名领取一个任务；jobId 以字符串传递，attemptNo + fencingToken 绑定本次租约。
2. 读取创建任务同一事务冻结的需求、阶段、资产编号及可选的顶层 `imageOptions`。新任务的 `imageOptions` 必须完整匹配 `{ resolution: '2K'|'4K', orientation: 'LANDSCAPE'|'PORTRAIT' }`，非法枚举在供应商调用前拒绝；无该字段的历史任务继续使用原配置尺寸。后端重新校验资产状态与使用权限后签发 120 秒读 URL；不向 Runtime 交付数据库或 COS 长期密钥。
3. 校验输入大小及 SHA-256。仅允许显式配置的存储 origin；拒绝重定向，不对模型返回的任意 URL 发起请求。
4. 先调用 apilio 的 chat/completions 生成提示词，再使用独立配置的图片模型调用 images/generations 或 multipart images/edits。立面可零参考文生图，也可带案例/用户参考图。每个槽位单独生成，实际图片通过原格式和摘要验证后上传。
5. 每 15 秒续租，丢失租约时中断在途请求。每个结果的源事件 ID 稳定，网络响应丢失最多重试三次同一结果事件。
6. 全部结果回写后发送完成屏障；后端继续做格式扫描、图片重编码、腾讯 IMS 内容审核及独立对象固化，再按有效槽位结算。
7. 供应商失败或付费结果未知时，结束当前尝试，由后端按已收到且审核通过的结果结算；不盲目重试付费请求。SIGTERM 或租约丢失保留日志，后续领取可复用已完成的提示词/图片，未知付费调用需要人工核对。

后端最多领取三次、任务创建 30 分钟后不再领取；调度器处理排队超时、取消及完成后的结算。服务健康仅代表轮询存活，不代表供应商生图质量合格。

## 配置（全部值通过未跟踪的 `.env.runtime` 或秘密托管注入）

|变量|用途|
|---|---|
|ZS_AI_CORE_URL|后端内部 origin；网络必须限制 Runtime 身份访问 internal-api，跨主机使用 HTTPS/mTLS。|
|ZS_INTERNAL_SECRET|与后端相同的内部签名密钥；不发送给供应商。|
|ZS_AI_API_KEY|apilio Bearer 密钥；不发送给后端或存储。|
|ZS_AI_BASE_URL|默认 https://api.apilio.ai/v1，需 HTTPS，不含凭据、查询串。|
|ZS_AI_MODEL|提示词模型，沿用既定 gpt-5.6-sol。|
|ZS_AI_TEMPERATURE|默认 0.7，范围 0–2。|
|ZS_AI_IMAGE_MODEL|主图模型（必填，无默认值）；平面和立面都优先使用它，需确认渠道实际支持及同步返回协议。|
|ZS_AI_IMAGE_MODEL_FALLBACK|可选；主图模型 HTTP 503 时的第一备用模型，默认 `gpt-image-2-all`。仅 503 触发；4K、524、超时不切换。|
|ZS_AI_IMAGE_MODEL_2K|可选；2K 最后备用模型（如 `gpt-image-2.5-sunburst-2k`）。主模型与第一备用都 503 时才使用；4K、524、超时不切换。|
|ZS_AI_IMAGE_SIZE_FLAT|无 `imageOptions` 的历史平面任务尺寸，默认 `1024x1536`，保持原有 `宽x高` 自定义格式兼容。|
|ZS_AI_IMAGE_SIZE_ELEVATION|无 `imageOptions` 的历史立面任务尺寸，默认 `1536x1024`，保持原有 `宽x高` 自定义格式兼容。|
|ZS_AI_IMAGE_QUALITY|可选；`low/medium/high/auto`，留空不发送该参数。`high` 提升清晰度但单张费用更高。|
|ZS_AI_REQUEST_TIMEOUT_MS|单次供应商请求（chat/images/图片下载）超时，默认 180000；4K/高质量档建议 300000。范围 30000–600000。|
|ZS_AI_STORAGE_ORIGINS|逗号分隔的 HTTPS 私有 COS/CDN origin 白名单；必须包含签名读写 URL 的实际 origin。|
|ZS_AI_IMAGE_URL_ORIGINS|可选；图片回包为 URL 模式（如 gpt-image-2 经 apilio 代理返回 `webstatic.aiproxy.vip`）时允许下载的 HTTPS origin 白名单，逗号分隔。默认仅允许与 AI base 同源；渠道 CDN 不同源时必须显式配置。|
|ZS_AI_STORAGE_MODE|可选，默认 `https`。`local-fs` 为开发模式：后端 LocalObjectStorageAdapter 签发 `local://` 内网地址，引擎直接读写与后端共享的资产根目录（见下节）；绝不用于生产。|
|ZS_AI_STORAGE_ROOT|`ZS_AI_STORAGE_MODE=local-fs` 时必填；必须与后端 `zhongshu.design.asset.storage-root` 指向同一目录。|
|ZS_AI_JOURNAL_DIR|直接 Node 启动时必填；Compose 固定为 /data。|
|ZS_AI_JOURNAL_FS_PROOF_SHA256|必填；卷内 `.capacity-proof.json` 原始字节的 SHA-256。缺失、不匹配或证明字段不合格时，所有付费外呼 fail closed。|

所有文本及图像模型名必须原样满足 B Provider 收据合同 `[A-Za-z0-9_.:/-]{1,64}`。Runtime 不会裁剪或 trim 配置；包含空格、非法字符或超过 64 字符会在启动配置加载时失败，早于积分预留和任何供应商调用。

## 内部入口：补充需求润色（复用 A 能力）

除 `/health` 外，本进程提供唯一一个业务入口，供后端调用；出图仍是本进程主动领任务，两者互不影响。

|项|说明|
|---|---|
|路径|`POST /internal/note-polish`，请求体 JSON `{ "receiptId": "B 已持久化的稳定 taskKey", "note": "≤200 字", "line": "FLAT" \| "ELEVATION" }`，≤4KB，超限 413；缺 receiptId 拒绝|
|鉴权|与 runtime → 后端同一套 HMAC：`HMAC-SHA256(ZS_INTERNAL_SECRET, "{timestamp}\nPOST\n/internal/note-polish\n{sha256(body)}")`，头 `X-ZS-Timestamp`（秒，±300 秒内）、`X-ZS-Signature`（hex）。未通过一律 401，不暴露细节|
|成功|`200 { "polished": "…" }`，输出限制 200 字|
|失败|`429 { "error": "BUSY" }` 并发上限 4；`400` 入参或 receiptId 非法；`409 RECEIPT_CONFLICT/OUTCOME_UNKNOWN` 表示同 receipt 篡改或历史付费结果未知、禁止重投；`503 STORAGE_UNAVAILABLE` 表示 Journal 空间门禁失败；`502 PROVIDER_FAILED` 为首次供应商失败（上游正文不回传）|
|密钥|`ZS_AI_API_KEY` 仍只在本进程，后端不再需要持有供应商密钥|
|网络|Compose 默认只发布 `127.0.0.1:8081`。后端与本进程不在同一台主机时，需把该端口放到内网并限制只允许后端访问（建议同时启用 mTLS/内网网关）|

`node src/main.mjs --check` 仅校验配置结构，不发起真实请求。`node --test test/*.test.mjs` 使用本地替身，无真实消费。`docker compose build` 构建镜像，`docker compose up -d` 会启动真实轮询，必须完成下方人工验收前置条件后再执行；本轮未执行此部署命令。

冻结选项对应的供应商请求尺寸如下：

|清晰度|方向|请求 `size`|
|---|---|---|
|2K|横向 `LANDSCAPE`|`1536x1024`|
|2K|竖向 `PORTRAIT`|`1152x2048`|
|4K|横向 `LANDSCAPE`|`3840x2160`|
|4K|竖向 `PORTRAIT`|`2160x3840`|

4K 使用 GPT Image 2 的 3840 最大边规格，总像素约 829 万，不请求 4096。`imageOptions` 同时作用于无参考图的 `images/generations` 和有参考图的 `images/edits`。Runtime 在上传和成功回写前读取 PNG IHDR 或 JPEG SOF 记录原生宽高；供应商实际返回尺寸与请求档位不一致时仍按返回内容入库展示，不以缩放或裁切伪装档位。单张输入和输出仍限制为 20MB；URL 或 `b64_json` 图片超限以 `PROVIDER_IMAGE_TOO_LARGE` 失败，图片 JSON 信封超过 20MB 图片的 base64 容量时以 `PROVIDER_IMAGE_RESPONSE_TOO_LARGE` 失败。代码已覆盖请求和响应像素合同，但供应商真实调用、画质及费用仍需账户验收。

## 立面商业渲染口径

- 立面最终图是商业级建筑摄影效果图：真实天空、庭院铺装、绿化树木、自然光影和材质反射，不是白底制图。
- 画面内不出现任何文字、图名、风格/材质说明、信息条、图框、尺寸、表格、公司名称或水印。
- 2K/4K 参考图均按原图提交，不压缩、不转码；4K 出图保持供应商返回的原生尺寸（通常 3840×2160）。
- 4K 参考 edits 仍受 `ZS_AI_REQUEST_TIMEOUT_MS` 约束，生产建议不低于 300000。

## 平面标注口径（flat-20261019.4）

平面只标整体面宽与整体进深；房间内只写房间名称，不标房间净尺寸、房间面积、建筑面积、使用面积，不绘制面积统计表、房间数量表或其他数据表格。

- 整体面宽/进深来自用户输入或冻结需求；不得把面宽×进深直接写成建筑面积。
- 房间只保留中文名称、楼梯“上/下”和唯一指北针“北”；无把握的名称不臆造。
- 参考图只用于核对轮廓、入口、楼梯、房间关系和房名；参考图的房间尺寸/面积/表格不复制到新图。
- 图框、标题栏、图名和楼层标题保留；标题栏只填写图名、楼层和整体面宽/进深。
- 这些是模型输入约束，不是结构化几何计算或施工校核；实际布局仍需设计人员复核。

## 本地真跑（local-fs 开发模式，T15 补）

前提：本地后端已按 `local,pg,zsdev` 启动（内部签名密钥用 zsdev 默认值即可），`后端程序/.env` 已注入 `ZS_AI_API_KEY`，并确认供应商支持的图像模型名。在 `ai-runtime/` 下执行：

```bash
ZS_AI_CORE_URL=http://127.0.0.1:48080 \
ZS_INTERNAL_SECRET=<B 内部签名密钥> \
ZS_AI_API_KEY=<供应商密钥> \
ZS_AI_IMAGE_MODEL=<渠道实际图像模型> \
ZS_AI_STORAGE_MODE=local-fs \
ZS_AI_STORAGE_ROOT="$TEMP/zhongshu-assets" \
ZS_AI_JOURNAL_DIR="$TEMP/zs-ai-journal" \
ZS_AI_JOURNAL_FS_PROOF_SHA256=<卷能力证明文件SHA-256> \
node src/main.mjs
```

引擎每 3 秒领取一次任务；小程序点「生成平面方案」后任务会在数秒内被领取并真实出图。`local-fs` 模式直读/直写与后端共享的资产根目录（含路径越界防护），绕开 `local://` 内网地址无法走 HTTPS 白名单的限制；生产切 COS 后仍走 `https` 白名单模式。

容器以非 root 身份运行，根目录只读，移除 capabilities，并限制内存、进程、CPU。`/data` 必须是 Runtime 用户所有的 0700 目录。日志卷包含提示词与图片，是业务数据，不能作为公共静态目录。重启时必须保留 started、completed/UNKNOWN 及 core-event outbox；自动清理不得删除未 ACK 或未知事实。

64MiB 健康水位只是运维报警，不等同容量预留。每次付费外呼前，Runtime 会在同一 Journal 文件系统真实写入并 fsync 容量文件：图片按最大 20MiB 原图的 base64 Journal 峰值另加余量，每个并发槽位独立预留；同时实际占用 3 份、每份 2MiB 的紧急文件，分别承受连续的 trace、UNKNOWN outbox 和 completion ENOSPC。分配、fsync、`st_blocks` 非有限安全整数或物理块不足都会在供应商外呼前 fail closed。

started、REQUESTED、trace DISPATCHED 等前置持久化可能消耗 emergency，因此主模型请求和每一次 fallback 请求在实际 HTTP 外呼前都会重新校验证明、当前调用的物理预留、三份完整 emergency 和操作余量；补齐失败不发送该次供应商请求。计划调用的公司积分 `prompt-call-events/DISPATCHED` 只在最终容量门禁通过后发送，因为 B 的 SERVICE_COST 合同一旦确认 DISPATCHED 就进入保守 UNKNOWN、禁止再降级为 NOT_SENT；门禁失败仍从 RESERVED 走 NOT_SENT 释放。Runtime 分开记录 `dispatchAttempted` 与供应商实际发送：DISPATCHED 回执丢失时，资金进入 durable UNKNOWN、trace 仍记录实际未调用供应商的 NOT_SENT；其后不得重投。进程正常启动时也必须先通过同一 proof/emergency 检查，不能等到首个业务请求才暴露卷错误。

`st_blocks` 只验证本次探针确实获得了报告的物理块，不能证明云盘底层不是 thin-provision。部署前必须由卷管理员验证目标是 Runtime 独占写入的本地 Linux ext4/xfs thick 卷，并在该卷根目录写入 0600、Runtime owner 的 `.capacity-proof.json`：`{"version":1,"filesystem":"ext4|xfs","allocation":"thick","exclusiveWriter":true,"volumeId":"..."}`；配置其精确 SHA-256。Runtime 同时校验文件 owner、权限、设备号、内容哈希和字段。NFS、压缩、去重、thin-provision、共享写入或不可靠报告 `st_blocks` 的卷禁止部署。

容量分配使用同步 write/fsync，会阻塞 Node 事件循环。上线前必须在真实目标卷以四图片槽位峰值压测：单槽预留 p99 不超过 1 秒、四槽连续预留总 p99 不超过 5 秒（小于 15 秒租约心跳的三分之一），同时 `/health` p99 不超过 1 秒；任一不满足即禁止部署，需改用独立容量守护进程/worker thread 后复审，不能调大租约掩盖。

Journal 默认不做按时间删除。监控需告警总字节、未 ACK outbox、UNKNOWN 数和损坏文件；只有后端已可靠记录相同 callId/attempt/fencing 的终态 ACK，且超过经业务批准的保留期后，才允许离线归档对应 completed 事实。UNKNOWN、未 ACK outbox、started 防重文件和损坏待审文件不得自动删除。

启动时 Runtime 只读巡检遗留的非 emergency `.capacity` 文件并记录 `runtime_stale_capacity_detected` 数量，绝不自动删除。恢复必须先停止全部共享该卷的 Runtime，逐项核对对应 started/UNKNOWN、供应商账单和后端终态，再由人工删除确认无活跃调用的遗留预留；运行中删除可能破坏其他进程的付费容量保证。

## B 后端契约与当前边界

Runtime 当前沿用 A 的内部路径和字段合同（`/internal-api/design/v1/ai-jobs/...`），但只连接 `ZS_AI_CORE_URL` 指向的 B 后端。B 后端必须独立实现 claim、inputs、prompt-calls、call-traces、result-events、completion-events 等相同合同，并在资金钩子中接入公司积分；未完成该适配前，本目录只能通过离线 fixture 验证，不能视为可部署联调。供应商 driver 也只覆盖现有同步 OpenAI 兼容协议，不支持异步任务 ID 轮询。

## 供应商验收前必须核对

### 与后端同步切换 COS

后端切换 COS 前先迁移本地历史资产。Runtime 同时设置 `ZS_AI_STORAGE_MODE=https` 和 `ZS_AI_STORAGE_ORIGINS=https://zhongshu-design-assets-1397776231.cos.ap-shanghai.myqcloud.com`，保留原日志目录以继续处理既有任务。Runtime 不需要 COS 永久密钥，只使用后端签发的输入 GET 和输出 PUT URL。平面和立面共用此链路，生成后的输出先进入 `ai-quarantine/`，校验后由后端固化到 `accepted/ai/`。

`node src/main.mjs --check` 只验证配置；真实 COS 权限、图片上传和任务结果仍需实际验收。

- 项目选择的是 apilio。图片模型的参数、响应形态、额度和计费需要供应商当前官方合同及实际账户验收。图片回包支持同步 `data[0].b64_json` 与 `data[0].url` 两种形态：URL 模式仅下载 `ZS_AI_IMAGE_URL_ORIGINS` 白名单内的 HTTPS origin、拒绝重定向、下载后重算魔数与摘要（已实测 apilio `gpt-image-2` 返回 `webstatic.aiproxy.vip` CDN 链接）。不支持任务 ID 异步轮询协议；若实际套餐采用该协议，必须补供应商适配器后才能启用，不得仅修改模型名后上线。
- 确认 chat 模型支持输入图片、temperature 和 max_tokens；确认图生图参数 `image[]`、单张 `n=1` 及图片模型输出格式。需求传给模型不保证建筑结构正确，结果仍需设计人员验收。
- 验收平面→选择→立面→选择→冻结版本，以及部分失败、超时、取消、进程重启、迟到回写；逐笔核对供应商实际费用与设计点结算。
- 为账户设置实际金额上限、只读用量核对和报警；对外出站仅开放所选供应商与存储域名。入口健康 /health（8081）不要当作可公开管理接口。

## 回退

先停止领取新任务，保留日志卷和所有账务事实；已有任务交给仍在运行的后端调度器结算。不得回退到会提前按首张图片结算的旧调度器，也不得恢复隔离区对象直接作为已审核资产的路径。供应商返回未知时按任务 ID 核对，不手工重放扣点流水。
