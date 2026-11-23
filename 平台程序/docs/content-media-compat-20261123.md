# 内容素材格式兼容与分身导入

本次只扩展 `CONTENT_ASSET`，工程、财务、合同及设计附件保持各自类型校验。

## 处理链路

1. 客户端先选择文件，再以实际文件格式申请素材票据。原件上限为 50 MiB。
2. 上传完成后，B 先验证原件大小和 SHA-256，再按真实媒体内容解码。
3. 图片转为 PNG（JPEG 保留 JPEG），视频转为 H.264/AAC MP4，音频转为 MP3。多声道素材统一混为立体声；图片最大 4000 万像素，音视频最长 1 小时；转换后的素材仍不得超过 50 MiB。
4. `accepted_size_bytes` 和 `accepted_sha256` 描述规范化结果；原始 `declared_mime`、`size_bytes`、`sha256` 保留作为上传事实。素材元数据、下载类型和提供给模型的对象使用规范化结果。
5. `complete` 可能返回 `VALIDATING`；客户端应确认同一 assetId，不应重新上传或提交付费任务。损坏或类型不符返回 `REJECTED` 及 `message`；组件不可用、超时或存储故障保留可重试状态。

支持图片 JPEG/PNG/WebP/GIF/BMP/TIFF/HEIC/HEIF/AVIF；音频 MP3/M4A/AAC/WAV/OGG/FLAC/AMR/3GP/WebM；视频 MP4/MOV/WebM/MKV/AVI/3GP/3G2/MPEG/M4V。仅识别扩展名或修改 MIME 不能构成兼容；服务端会验证实际编码。多图 HEIF、GIF 只取第一张静态图，未承诺保留动画或全部连拍帧。

## 数字人分身及声音

原生小程序使用相同素材上传流程，随后调用：

```http
POST /admin-api/franchise/content/personas/{personaId}/clone-asset-uploads
{"kind":"AVATAR|VOICE","assetId":"..."}
```

返回 `uploadId`、`status: UPLOADED`、`providerStatus: UNVERIFIED`、`assetId`、`mimeType` 和 `sizeBytes`。后续仍通过既有 `sourceUploadId` 提交制作，原积分确认、授权确认和幂等机制不变。

导入只允许当前上传者本人的已验收素材。服务端重新读取 B 对象并核对规范化摘要，不接收外部下载 URL。视频保持供应商要求的 5 秒至 30 分钟和 360p 至 4K；声音为 5 秒至 3 分钟、规范化后不超过 20 MiB。规范化视频最长边缩至 1920 像素。

同组织、用户、岗位、人物、种类和 assetId 只形成一条导入记录。重复确认成功记录不再次向供应商上传。并发或网络暂不可用返回 `CLONE_UPLOAD_RETRYABLE`（1114000012），客户端应保留 assetId 并重试确认。180 秒租约防止并发传输，最终落库检查 lease token。极端进程中断发生在供应商接收文件后、B 固化回执前时，下一次可重新签发并上传同一已验收素材；这一动作不提交制作或预扣积分。

旧 H5 直传和原回执仍兼容。新导入记录不接受旧的客户端 `complete/file-receipt` 接口标记成功。

## 部署要求

`deploy/platform/Dockerfile` 安装 FFmpeg、ffprobe 和 libheif examples。必须重建 B API 镜像后发布；只有合并代码不会为已运行的容器增加解码器。可通过 JVM 系统属性配置 `platform.media.ffmpeg`、`platform.media.ffprobe` 和 `platform.media.heif`（默认 `ffmpeg`、`ffprobe`、`heif-convert`）。

生产 Jammy 的 `libheif1` 依赖 AV1/HEVC 解码库；Ubuntu 24.04 CI 使用拆分包，显式安装 `libheif-plugin-libde265`、`libheif-plugin-x265`、`libheif-plugin-aomdec`，避免 `--no-install-recommends` 漏掉编解码器。只检查可执行文件存在不能证明对应编码可用，必须通过实际素材转换测试。

每次 FFmpeg 转换最多 90 秒、2 个编解码线程；probe 最多 15 秒，HEIF 解码最多 45 秒。HEIC/HEIF/AVIF 统一交给 libheif，不依赖 Jammy 镜像中旧版 FFmpeg 的 AVIF 支持。FFmpeg 输入仅开放本地 file/pipe 协议；Hifly PUT 只使用服务端验证过的 `https://upload.hifly.cc` 地址、不跟随重定向、不携带平台或供应商 API Token。转换工作目录计入 spool 配额，多图 HEIF 按目录总量限额；进程中断后由 spool 恢复逻辑清理。规范化素材导入供应商最多同时运行两笔，限制持有的媒体字节内存。

## 验证

`ContentMediaNormalizerTest` 用实际生成的 HEVC MOV、M4A/AAC/WAV/OGG/FLAC、WebP/GIF/AVIF、单图及多图 HEIF 检查规范化结果，并拒绝仅有文件头的假 MP4、媒体家族不符及播放列表。测试要求与生产相同的 FFmpeg/libheif 工具，HEIF 测试通过 `heif-enc` 生成夹具。

`LocalAssetServiceTest` 检查规范化摘要、原始声明不可变、下载类型、素材属主边界和损坏文件拒绝；`ContentCloneUploadPgIntegrationTest` 检查供应商 PUT 请求、重放不重复上传、并发租约、声音时长、完整性和权限撤回。

本地受限环境的实际 Java 规范化探针验证了 HEVC MOV、M4A、WAV、WebP、AVIF，以及 libheif 官方测试样本的 HEIC/HEIF；完整 Spring/Maven/PostgreSQL 测试以本次 CI 结果为准。没有把手机真机操作或真实供应商制作当成本地自动测试的结果。
