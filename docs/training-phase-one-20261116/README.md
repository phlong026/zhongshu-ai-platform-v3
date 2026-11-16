# 课程培训第一阶段开发与验收

2026-11-16。本阶段开发及**隔离本地环境验收通过**。生产部署、微信小程序正式版发布和真机文档呈现复验尚未执行。

## 交付入口

- 本地管理后台：<http://127.0.0.1:5178/training/courses>；资料库：<http://127.0.0.1:5178/training/materials>。
- 小程序：已登录并选择工作身份后，从“我的”中的学习、课程、资料入口进入原生学习中心。
- 图文验收报告：[打开报告](../../deliverables/课程培训一期开发验收_20261116/验收报告.html)。[界面对照检查](../../deliverables/课程培训一期开发验收_20261116/design-qa.md)。
- 方案及效果图沿用 `deliverables/课程培训管理方案_20261116`。

## 已实现

| 管理后台 | 小程序与服务端结果 |
| --- | --- |
| 四步编辑课程：基本信息、课程内容、学习权限、预览发布 | 学习中心、分类、搜索、课程详情、分章目录、配套资料 |
| 文件/文件夹/拖放批量上传，3 路并发，每批最多100个；4MiB分片、暂停、重试、重新选择原文件续传 | 文件落到私有持久目录；整文件SHA-256校验、请求幂等、分片去重 |
| 资料分类、版本记录、处理状态、失败重试、预览和原件下载 | 视频/音频真实转换；Office转PDF，后台通过本地PDF.js直接渲染；图片解码校验 |
| 目录拖动排序，课时主课件及配套附件，允许下载开关 | 小程序视频、音频播放，文档打开及配套资料；附件原件下载受权限控制 |
| 草稿自动保存、修订冲突检测、提交审核、退回原因、发布、下架 | 只有审核发布且文件已可用的课程才展示；草稿修改不影响已发布快照 |
| 组织、岗位、指定人员、生效及截止时间 | 依据当前工作身份由服务端判定可见范围，播放/预览/下载再次校验权限 |
| 资料上传新版本；查看课程引用 | 课程固定引用具体版本，资料更新不自动替换已发布课程 |
| 管理员登录文案清理 | 已删除“门店老板和员工请在小程序登录并选择工作身份”“如需开通管理员权限，请联系系统负责人” |

学习进度由服务端记录有效播放区间，跳到结尾不算完成；课时完成比例可配置。文档需要服务端计时满30秒后主动确认已读；已读不代表掌握知识。更新课时文件版本后，当前版本重新计算进度。

旧目录6门课程、38个课时已迁移为草稿，保留原ID和标题。没有补造培训视频，也没有将旧的手工勾选进度当作服务端完成记录。运营人员需要导入正式资料、设置学习范围，再审核发布。

本阶段包含私有文件服务和本地转换处理，按单台B服务实例落地。多实例部署需要先适配共享存储或对象存储。培训任务、考试、培训统计和AI功能不在本阶段范围。

## 验收结果

| 检查 | 实际结果 |
| --- | --- |
| 真实PostgreSQL后端回归 | **64项通过，0失败、0跳过**：培训15、原业务模块17、日志脱敏32 |
| 真实HTTP联调 | **40项通过**：10种实际文件格式处理、幂等、发布、权限、快照、原件字节一致、Range206/416等 |
| 一次选择20个混合文件 | **20个上传任务、20个独立版本、20个可用、0失败** |
| 浏览器实际断网后恢复 | 约17MB、5个分片；断网时已有0/1，恢复仅上传2/3/4，同一任务和版本完成，无重复建档 |
| 微信开发者工具主链路 | **7项通过、0异常**：真实API课程列表/详情、签名视频播放与完成、音频完成、切岗权限变化 |
| 开发者工具文档补充验收 | **3项通过**：下载/打开调用成功，30秒前不能确认，满30秒确认后服务端与页面显示课程100%完成 |
| 发布及版本补充检查 | **6项通过**：退回/重提/发布、两个课程继续引用V1、六门旧课程草稿、续传结果、运行日志不含媒体票据 |
| 管理后台构建 | Vue类型检查、生产构建通过；PDF中文表格在浏览器实际可见 |
| 小程序回归 | 根目录完整 `npm run check` 通过，84份WXML、56个原生页面、238份JS、82份JSON检查通过，主包2,025,859字节，小于2MiB |
| 窄屏 | 后台390px无横向溢出；小程序使用官方工具390×844模拟器验收 |
| 视觉检查 | 暖棕品牌和既有组件保留；修复资料表窄屏布局、PDF空白及状态标签对比度；见design-qa.md |

证据保存在交付目录 `images/` 和 `evidence/`。原始测试脚本、日志、隔离数据库、转换工具及本地凭据在已忽略的 `qa/training-phase-one/`，不包含在图文交付包中。所有验收素材为隔离环境生成的测试文件。

开发者工具支持验证文档下载和打开API调用；本文没有将其等同于微信真机PDF/Office呈现验收。真机打开、生产HTTPS域名及微信域名白名单需在发布环境复验。

## 开发文件位置

小程序仓库 `E:/众墅之家AI赋能平台V3版`：

- `miniprogram/api/training.js`、`miniprogram/pages/account/training/`：真实API与原生学习页面。
- `miniprogram/app.json`、`pages/account/index.js`、`pages/profile/profile-page.js`、`pages/profile/profile.wxml`：入口及真实课程接入。
- `scripts/verify-profile-training-tasks.cjs`：真实API边界及既有个人页回归。

后台仓库 `E:/众墅之家AI赋能平台V3版-后端/平台程序`：

- `yudao-platform-operations/src/main/java/cn/iocoder/yudao/module/platform/training/`：资料、课程、权限、进度、上传和私有媒体控制器。
- 同模块 `src/main/resources/db/migration/pf/V20261116.731__training_phase_one.sql` 和 `V20261116.732__training_legacy_catalog_drafts.sql`：新增培训数据表、菜单和旧目录草稿。
- 同模块 `src/test/java/cn/iocoder/yudao/module/platform/training/TrainingIntegrationTest.java`：15项真实PG培训回归。
- `admin-ui/src/api/platform/training.ts`、`admin-ui/src/views/platform/training/`：课程、资料、上传、选择资料及PDF预览组件。
- `admin-ui/build/vite/training-pdf-assets.ts`、`admin-ui/vite.config.ts`、`admin-ui/package.json`、`admin-ui/pnpm-lock.yaml`：固定PDF.js依赖，打包本地字体与解码资源。
- `admin-ui/src/hooks/web/useAdminTableLayout.ts`、`admin-ui/src/utils/projectNavigation.ts`：兼容新表格和菜单路由。
- `admin-ui/src/views/Login/components/LoginForm.vue`：两句提示删除。
- Web框架中的 `SensitiveLogRedactor`、`ApiAccessLogInterceptor`、`GlobalExceptionHandler` 和对应测试：媒体票据路径脱敏。

项目原有未提交内容已保留。本次未执行Git提交、合并或远程推送。

## 联调接口

接口延续项目 `/admin-api` 和当前工作身份认证约定。

| 前缀 | 用途 |
| --- | --- |
| `/training/admin/options` | 分类、岗位、组织和分片大小 |
| `/training/admin/courses` | 草稿列表、读取、创建、带revision保存 |
| `/training/admin/courses/{id}/checks`；`/{submit|reject|publish|withdraw}` | 发布检查与审核操作 |
| `/training/admin/materials`；`/{versionId}`；`/{versionId}/retry`；`/{versionId}/ticket` | 资料版本、处理重试及受控预览下载 |
| `/training/admin/uploads`；`/{id}`；`/{id}/parts/{part}`；`/{id}/complete` | 初始化、续传查询、分片PUT及完成 |
| `/training/app/courses`；`/courses/{id}`；`/materials` | 当前工作身份下的可见课程及配套资料 |
| `/training/app/courses/{id}/lessons/{lesson}/play`；`/progress`；`/read` | 播放会话、进度心跳及确认已读 |
| `/training/app/courses/{id}/materials/{version}/ticket` | 当前课程附件授权 |
| `/training/media/{opaque-token}` | 私有流与Range读取；不暴露存储路径，票据摘要入库，读取时重验权限 |

## 部署交接

1. 沿用B库与B服务部署。使用数据库owner身份执行新增Flyway迁移，应用仍使用原有受限账号。先备份数据库及私有文件目录，确认731/732迁移成功和管理员菜单授权。不要复制验收账号或测试数据库。
2. 配置服务端私有持久目录，禁止作为Nginx静态目录公开。配置示例如下，值应由实际部署环境提供：

   ```properties
   training.storage-root=/var/lib/zhongshu/training-private
   training.ffmpeg=/usr/bin/ffmpeg
   training.ffprobe=/usr/bin/ffprobe
   training.libreoffice=/usr/bin/soffice
   training.pdfinfo=/usr/bin/pdfinfo
   ```

   运行服务的账号需要目录读写权限及转换工具执行权限。检查FFmpeg含H.264/AAC/MP3编码能力、LibreOffice与中文字体、Poppler pdfinfo可用。转换进程带超时，失败保留错误供管理员重试。本次本地使用官方LibreOffice提取版，没有修改生产转换环境。
3. 后端按原Java17/Maven流程构建发布。管理后台遵循项目已声明的Node `>=24.14.1 <25`、pnpm `11.19.0`，执行 `pnpm install --frozen-lockfile`、`pnpm ts:check`、`pnpm build:prod`。本次新增固定版本 `pdfjs-dist@6.5.438`。
4. 部署完整后台构建产物，尤其是PDF worker `.mjs` 与 `training-pdf/` 下的cmaps、standard_fonts、wasm、iccs和LICENSE。Web服务须以JavaScript MIME响应 `.mjs`、以 `application/wasm` 响应WASM；不要只复制主JS/CSS。预览不依赖公网CDN。
5. 反向代理允许4MiB分片PUT，请求体上限应留足余量（如8MiB）；转发Range头并保留206/416响应。代理访问日志也要隐去 `/training/media/` 后的票据，避免把临时授权链接写入外围日志。
6. 小程序继续使用既有生产API配置，配置HTTPS request/downloadFile合法域名，在微信真机复验视频、音频、PDF/Office打开、下载分享、切岗与30秒已读；后台导入至少一套正式资料并审核发布后，再走小程序发布流程。
7. 同时备份数据库与私有文件，保证恢复时课程版本引用和文件一致。新版本上传不会自动替换旧课程；替换主课件/附件后需要重新审核发布。

本次验收服务仅绑定127.0.0.1：后台5178、API48089、隔离PG25432、Redis26479。测试关闭验证码及域名校验的设置只用于隔离联调进程/项目副本，未降低生产配置。

## 回归复现

小程序仓库运行 `npm run check`。后台仓库在现有真实PG隔离测试支持配置下运行：

```powershell
mvn -B -pl yudao-platform-operations -am "-Dtest=TrainingIntegrationTest,OperationsIntegrationTest,SensitiveLogRedactorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

默认测试支持使用Testcontainers；无Docker时可按项目 `PlatformPgIntegrationTest` / `TestPostgres` 的外部PG隔离配置运行。用例创建一次性测试数据库，不能连接生产环境运行。HTTP和官方小程序自动化验收脚本及输入位于本地 `qa/training-phase-one/`；重复执行应使用新的隔离数据，避免把上一轮课程数量计入新一轮断言。
