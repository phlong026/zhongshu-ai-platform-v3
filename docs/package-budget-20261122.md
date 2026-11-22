# PKG-01：小程序包体与资源引用整改

基准为主线 `7985fa604a3a376777d8cea4f799c112cdd22d1b`，包含 C03、C04、AUTH03、AUTH04 和 DOC-01 前端文档修订。数字来自仓库按 `project.config.json` 的 `packOptions.ignore` 汇总的文件字节数；不是微信开发者工具编译上传后的包体，也不用于解释平台当前官方限额。

## 结果和项目预算

沿用每包 2 MiB（2,097,152 B）的项目预算，增加每包至少 128 KiB（131,072 B）的增长余量。`verify-package-resources.cjs` 同时报告实际包体和剩余字节；余量不足时明确报出包名、剩余量和要求值。`npm run check` 与 `npm run check:packages` 均执行对应门禁和回归。

| 包 | 修改前 | 修改后 | 修改后剩余量 |
| --- | ---: | ---: | ---: |
| 主包 | 2,087,918 B | 1,952,219 B | 144,933 B（约 141.5 KiB） |
| `pages/workflow` | 2,061,832 B | 1,892,985 B | 204,167 B（约 199.4 KiB） |
| `pages/build` | 1,087,298 B | 1,161,015 B | 936,137 B（约 914.2 KiB） |

主包和工作流包均满足约定余量；工程包承接专属图片后仍有充足空间。所有包总计从 8,624,779 B 降至 8,393,950 B，减少 230,829 B。

## 资源处理依据

- **材料横幅重定位**：`assets/materials-banner.jpg` 只由 `pages/build/home/index.wxml` 使用，迁入同分包的 `pages/build/assets/`。总资源清单同步路径，图片字节未改，Git blob 保持 `8891701b569b4b30b8f1ff3855ee8ac0de7de883`。
- **支付图片复用**：两套支付宝和微信 PNG 字节一致，共 60,620 B。`simulated-payment.wxml`、`simulated-cashier.wxml` 改为使用原有 `pages/workflow/assets/commission/`，与 `journey.wxml` 一致；另一套仅加入打包排除。付款状态和缓存保存支付渠道，不保存这两套图片路径。
- **退休的平面参考图**：`pages/workflow/assets/biz-reference-plan.jpg`（108,342 B）保留源文件，加入打包排除。核对了模板、当前动态图标枚举、原资源提取脚本和客户缓存路径：没有当前生成或持久化该路径的生产者。旧案例入口在授权后进入正式设计详情并请求 API，不恢复本机示例图。回归使用含该旧路径的本机客户缓存，验证正式入口读取 API、页面不展示旧图，同时缓存内容原样保留。
- **退休的主包图标**：以下 10 张图片共 61,994 B，仅加入打包排除：`approval.png`、`settings.png`、`review.png`、`headset.png`、`shield.png`、`brand-support.png`、`training-support.png`、`design-support.png`、`delivery-support.png`、`creation-publish.png`。已检查首页、业务、资料页、导航及财务快捷菜单的图标枚举与动态路径。当前资料页使用 `account-*` 图片，创作快捷入口固定为人物库、素材库、作品和任务。

主包的 `home-engineer-icon.png`、`home-chart-icon.png`、`fire.png`、`creation-works.png` 等有动态引用，继续打包。工作流中的 `biz-plan-result.jpg`、`biz-case-hero.jpg`、`biz-formal-plan.jpg` 等仍存在模板回退或历史缓存迁移用途，继续保留。没有修改客户、草稿、历史任务等业务数据。

## 校验覆盖与边界

资源检查新增静态相对 `image src` 的检查，同时支持单双引号，验证文件存在、未被打包排除、属于当前分包或主包。远端和临时 URL 不作为本地包资源处理；已有表达式中的绝对图片分支仍逐一检查。通用检查器不尝试执行任意动态路径表达式，因此对本次涉及的动态菜单、支付渠道增加真实页面数据和 WXML 渲染回归。

`scripts/verify-package-budget.cjs` 覆盖缺失图片、已排除但源文件仍在的图片、跨分包相对引用、条件图片分支、主包/分包余量的恰好达标与少一个字节边界，以及实际游客菜单、业务创作入口、工程横幅、两种支付渠道和旧案例入口。

在上述基准上，定向回归 11/11 通过，`npm run check:packages` 通过，完整 `npm run check` 的 18 组 runner、833 个累计测试实例全部通过；其中保留了 AUTH04 的 `verify-profile-cache-isolation.cjs` 门禁。累计数包含聚合命令中重复执行的用例。

本地使用 Node Page/WXML 确定性运行时及仓库完整检查，不等同微信原生渲染。最终编译、预览和上传包体仍需在微信开发者工具中核验。
