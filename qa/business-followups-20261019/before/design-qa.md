# 业务模块四页与关联页面视觉验收 · 2026-10-19

final result: passed

本轮以用户选定的四张效果图和根目录 `miniprogram/` 的微信原生渲染进行对照。上一轮报告归档于 `qa/business-module-redesign-20261019/before/design-qa.md`。

## 依据与证据

| 页面 | source visual truth path | implementation screenshot path | 同图对照 |
| --- | --- | --- | --- |
| 首页 | `效果图/业务人员四页改版方案_20261019/02_首页_方案2.png` | `qa/business-module-redesign-20261019/final-native/home-top.png` | [完整](qa/business-module-redesign-20261019/comparisons/home.png) · [局部](qa/business-module-redesign-20261019/comparisons/home-focus.png) |
| 客户 | `效果图/业务人员四页改版方案_20261019/10_客户_方案1.png` | `qa/business-module-redesign-20261019/final-native/customers-top.png` | [完整](qa/business-module-redesign-20261019/comparisons/customers.png) · [局部](qa/business-module-redesign-20261019/comparisons/customers-focus.png) |
| 创作 | `效果图/创作与设计页面第二轮方案_20261019/01_内容创作_方案1.png` | `qa/business-module-redesign-20261019/final-native/creation-top.png` | [完整](qa/business-module-redesign-20261019/comparisons/creation.png) · [局部](qa/business-module-redesign-20261019/comparisons/creation-focus.png) |
| 设计 | `效果图/创作与设计页面第二轮方案_20261019/04_AI设计_方案1.png` | `qa/business-module-redesign-20261019/final-native/design-top.png` | [完整](qa/business-module-redesign-20261019/comparisons/design.png) · [局部](qa/business-module-redesign-20261019/comparisons/design-focus.png) |

viewport：微信开发者工具原生 iPhone 12/13 (Pro)，逻辑窗口390×844、状态区47、pixelRatio3。四张源图均853×1844；SDK实际截图353×762。脚本等比归一到390px宽，未将SDK输出误作@3x原始像素。局部图按首个内容区裁切，消除头部高度偏移；尺寸在 `comparisons/manifest.json`。

state：张业务以业务员身份登录后的非空本地模拟数据。完整对照同时放置效果图、微信首屏、微信滚动底部。效果图数字是排版示例；实现页取实际授权记录，不以示例数字替换业务数据。负责人及发布、转化、设计保存状态另有原生截图。

## Findings 与迭代

- **[P2，已修复] 首页次要待办过高，统计标记单占一行。** [首轮证据](qa/business-module-redesign-20261019/comparisons-round1/home.png)。第二条也展开完整需求和独占行按钮，挤压最近办理。已改为首条完整展示、次条摘要及右侧操作，标记与统计同排，统计字体统一40rpx。原生坐标验证次条更矮且按钮在行内。[修复后](qa/business-module-redesign-20261019/comparisons/home.png)。
- **[P2，已修复] 设计头图过度裁切。** [首轮证据](qa/business-module-redesign-20261019/comparisons-round1/design.png)。已将建筑图限定于右侧66%，左侧52%承载标题和操作，恢复建筑轮廓；标题和说明受控分行，避免末字孤行。[修复后](qa/business-module-redesign-20261019/comparisons/design.png)。
- **字体混用已消除。** 原设计子页宋体和创作独立标题尺寸已统一。最终9处标题字体族、字号、字重、行高、颜色相同；首页/客户统计数字相同。[原生实测](qa/business-module-redesign-20261019/final-native/results.json)。

已打开检查最新四组完整图及局部图，没有剩余可执行的P0/P1/P2视觉问题。

## 五项检查

| 项目 | 结果 |
| --- | --- |
| 字体 | 无衬线 `-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif`；标题32rpx/46rpx、600；正文28rpx/42rpx；辅助24rpx/36rpx。9处标题在本设备均实测16px、600、23px行高；统计数字40rpx/52rpx，均实测20px/27px。没有宋体标题残留、遮挡或按钮截字。 |
| 间距与布局 | 主页28rpx左右边距、22rpx白卡圆角、24rpx卡间距；按钮14rpx圆角。详情保留原88rpx固定操作栏及安全区。无横向溢出，四页可滚动到底且导航可达。 |
| 颜色 | 米白#faf8f4、棕色#89512a、正文#141312、辅助#858991统一，表单、状态、边框与卡片沿用同一组色值。 |
| 图片 | 使用项目品牌Logo、图标、真实案例目录及建筑照片，未把整张效果图作为界面。案例图从原子包加载，失败有封面及重试入口；头图没有视频播放或待发布字样。SDK缩放会柔化截图，未据此认定源资产损坏。 |
| 文案与内容 | 客户、待办、预约、草稿及作品读取授权记录。三条示例链接有独立原文；发布前选账号。案例名称、面积和层数取原目录，方案/预算回存同一客户。 |

## 明确取舍与验证边界

四页统一现有紧凑品牌栏，未保留首页/客户效果图单独占高的装饰光带，以落实“和其他页面一致”。图标/照片沿用项目资产，精选案例保留原名称与参数。示例链接选择、原岗位待办、尺寸检索和立面入口增加后，部分内容需滚动；底部截图证明入口完整。微信状态区及安全区为原生平台内容。

原生9组流程检查通过、23张截图，覆盖两个账号、发布审核、线索分配/转客户及设计/预算保存。另有13张最终样式截图，9处标题一致，运行异常0，验证结束本地存储恢复校验通过。此前一次自动化截图连接超时，已恢复存储并重连后完整重拍；失败批次不作为最终证据。

Implementation Checklist：四页布局、子页字体/间距、非空数据、业务联通、发现项修复、原生重拍、同图对照均完成。当前没有需用户确认的视觉阻塞项。完整测试说明见 [业务验收说明](qa/business-module-redesign-20261019/验收说明.md)。
