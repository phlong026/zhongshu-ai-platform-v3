# 小程序手动发布首发收敛报告

## 范围

- 工作树：`/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3/.worktrees/joint-review-20261114`
- 基线 HEAD：`383608ed`
- 未提交；未修改后端、README、CHANGELOG 或评审文档。

## 修改文件

- `miniprogram/pages/studio/index.js`
  - 手填 `accountId` 元数据与 `douyin/channels` 平台选择。
  - 忽略旧 `selectedAccount/scheduled` 草稿，不发送 `scheduledAt`。
  - 创建记录采用 1..80 字符稳定幂等键：同会话/岗位/组织和同 payload 失败重试同键；内容或 scope 变化换键；成功清键；busy 防双击。
  - 创建成功/失败回调复核 session、orgId、postId；切岗迟到结果不导航、不提示。
  - `publications.page` 接入记录列表、筛选、详情、失败重试及迟到保护。
  - 映射真实 `accountId/publishText/tags/authorUserId/createTime`；明确非本人记录只读。
  - 历史 `scheduled` 显示“定时发布已关闭”，仅用户登记链接时走 verify→link。
- `miniprogram/pages/studio/index.wxml`
  - 移除定时入口和两个硬编码演示账号。
  - 移除无内容的“更多设置”空白弹层入口。
  - 文案改为“创建发布记录”“手动发布后登记链接”。
  - 增加记录列表/详情失败重试与非本人只读展示。
  - 已发布详情只保留链接复制，不提供修改链接；失败详情只展示原因，不进入回执页。
- `miniprogram/utils/platform-content.js`
  - 校验账号标识 trim 后 1..64 字符与平台枚举。
  - 历史 `scheduled` 在人工登记时按 publishing 一样先 verify 再 link。
- `miniprogram/api/franchise/content.js`
  - 注释明确 create 是手动元数据登记、不授权、不自动发布、不接定时；scheduled 仅历史语义。
- `scripts/verify-studio-manual-publication.cjs`
  - 新增真实 Page 行为测试。
- `scripts/verify-content-pages.cjs`
  - 更新首发手动登记校验契约。
- `package.json`
  - 新专项测试接入主 `check`。

## RED / GREEN

- RED：`node scripts/verify-studio-manual-publication.cjs`
  - 基线 HEAD 隔离 worktree 运行，exit 1。
  - 首个失败：旧 `selectedAccount` 草稿无法得到空的手填账号，证明原页面仍依赖演示账号模型。
  - 原始日志：`/tmp/zs-manual-publication-mini-red.log`
- GREEN：同命令 exit 0。
  - 输出：`verify-studio-manual-publication ✔ 手动账号、无定时、稳定键与历史scheduled人工登记通过`
  - 原始日志：`/tmp/zs-manual-publication-mini-green.log`

## 行为验收覆盖

- accountId trim、1..64 字符、平台只能 douyin/channels。
- payload 不含 scheduledAt；旧 scheduled/selectedAccount 草稿不回填。
- 页面不存在定时入口、演示账号入口或空白“更多设置”入口；失败文案不暗示平台账号授权。
- 失败重试同键、改账号换键、切岗换键、busy 防双击、迟到成功不导航、成功清键。
- 创建→receipt 人工 verify→link→records→detail 闭环。
- 仅 publishing/verify/scheduled 可从详情进入 receipt；published/failed 行为测试证明不会导航。
- records 现有筛选；scheduled 历史提示；publishText/tags/accountId/authorUserId/createTime 真实映射。
- records 失败显式重试；切岗迟到列表不回写。
- 明确非本人记录隐藏登记入口；后端 requireOwned 仍为真实授权边界。

## 主检查

- 命令：`npm run check`
- 结果：exit 0。
- 规模：183 JS、58 JSON、35 个原生页面；profile 32/32；auth 54/54。
- 原始日志：`/tmp/zs-manual-publication-mini-check.log`
- `node --check`（修改 JS/专项测试）与 `git diff --check` 通过。

## Scope guard

- 创建回调 scope：`sessionGeneration + orgId + postId + payload`。
- 列表/详情/receipt 读取 guard：请求代次 + `sessionGeneration + orgId + postId`。
- authorUserId 只用于明确非本人时控制操作展示，不作为授权；verify/link 仍由后端本人及组织校验。

## 未验证

- 未做微信真机视觉/交互验收。
- 未对真实后端部署环境执行创建、verify、link 联调。
- 未执行生产发布或上传。
