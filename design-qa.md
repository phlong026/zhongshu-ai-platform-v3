# 业务模块四页与关联页面视觉验收 · 2026-10-19

final result: passed

本轮对照四张用户选定效果图，并纳入后续明确要求：首页卡片、“今日待办”、功能顺序、设计首图、四入口和“精选灵感”、免审核发布及发布后链接登记。上一轮报告保存在 `qa/business-followups-20261019/before/design-qa.md`。

## 同图对照

| 页面 | source visual truth path | implementation screenshot path | 并排证据 |
| --- | --- | --- | --- |
| 首页 | `效果图/业务人员四页改版方案_20261019/02_首页_方案2.png` | `qa/business-followups-20261019/final-native/home-top.png` | [完整](qa/business-followups-20261019/comparisons/home.png) · [局部](qa/business-followups-20261019/comparisons/home-focus.png) |
| 客户 | `效果图/业务人员四页改版方案_20261019/10_客户_方案1.png` | `qa/business-followups-20261019/final-native/customers-top.png` | [完整](qa/business-followups-20261019/comparisons/customers.png) · [局部](qa/business-followups-20261019/comparisons/customers-focus.png) |
| 创作 | `效果图/创作与设计页面第二轮方案_20261019/01_内容创作_方案1.png` | `qa/business-followups-20261019/final-native/creation-top.png` | [完整](qa/business-followups-20261019/comparisons/creation.png) · [局部](qa/business-followups-20261019/comparisons/creation-focus.png) |
| 设计 | `效果图/创作与设计页面第二轮方案_20261019/04_AI设计_方案1.png` | `qa/business-followups-20261019/final-native/design-top.png` | [完整](qa/business-followups-20261019/comparisons/design.png) · [局部](qa/business-followups-20261019/comparisons/design-focus.png) |

viewport：微信开发者工具 iPhone 12/13 (Pro)，逻辑窗口390×844、状态区47、pixelRatio3。四张效果图均853×1844；SDK截图353×762。并排图统一到390px宽，保留源图、原生首屏及滚动底部；尺寸见 `comparisons/manifest.json`。

state：四页样式截图使用张业务的授权模拟数据；业务流程另以同一个业务兼岗人员切换专员、负责人测试。统计数字来自实际授权记录，效果图中的12等示例数字不作为数据源。

## Findings 与修复

- **[P2，已修复] 首页待办缺少卡片层次。** 当前采用暖色外卡、白色内卡和紧凑次要待办；统计数字均可跳转对应客户集合。功能顺序与用户反馈一致。[当前对照](qa/business-followups-20261019/comparisons/home.png)。
- **[P2，已修复] 设计首图裁切及文字覆盖。** 建筑图限定在右侧52%，用aspectFit完整显示，左侧48%承载标题和操作；原生坐标验证图片与文字无重叠。四入口、精选灵感及重复底部入口删除均按最新要求落实。[当前对照](qa/business-followups-20261019/comparisons/design.png)。
- **[P2，已修复] 发布链接排在长自动信息之后，首屏难以发现必填项。** 已把必填链接表单放在首卡，自动带入信息在后，保存按钮固定底部。原生坐标证明输入框完整位于操作栏上方。[调整前后](qa/business-followups-20261019/comparisons/receipt-order.png)，[实测](qa/business-followups-20261019/final-native/results.json)。
- **字体统一。** 首页、客户、创作、设计、客户详情、预算、作品、AI视频、来源页和发布链接页10处标题的字体族、字号、字重、行高与颜色一致；首页及客户统计数字也一致。

已逐张打开最新四组完整并排图，并查看新金额审批、客户一次签认、变更审核及发布表单截图。当前业务范围没有待修复的P0/P1/P2视觉问题。

## 五项检查

| 项目 | 结果 |
| --- | --- |
| 字体 | `-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif`。标题32rpx/46rpx、600，正文28rpx/42rpx，辅助24rpx/36rpx；10处标题实测16px/23px、600，统计实测20px/27px、600。 |
| 间距布局 | 主页左右28rpx、白卡圆角22rpx、卡间距24rpx、按钮圆角14rpx。详情表单使用同一字体、边框和安全区；四页底部与操作可达，首图无文字覆盖。 |
| 颜色 | 米白背景、棕色主操作、深色正文、灰色辅助信息统一。首页待办暖色底与白卡形成层次。 |
| 图片 | 使用独立品牌、图标和建筑素材；未把整张效果图作为页面。设计封面完整显示，灵感图加载失败可重试。候选平面和立面明确为模拟示意，保留参考版本与实际参数。 |
| 内容 | 四页默认非空；客户、跟进、预约、草稿、作品、参考链接可用。设计预算与正式业务关联同一客户；发布信息自动带入，链接登记后显示待回采。 |

## 取舍与边界

沿用项目紧凑品牌栏，未恢复效果图首页、客户页的独立装饰光带。设计首图优先完整显示建筑与操作，四入口替代源图两入口；案例使用本项目目录和参数。页面记录数量不同于效果图示例，长列表可滚动查看；底部截图保留这些状态。

原生主链9组、25张截图；审批办理14组、28张截图；最终字体10处、16张截图。三个最终批次运行异常为0，均恢复原有存储。并行工程页面落盘间隙和开发工具组件缓存中断后的批次已重新验证，不使用中断结果充当通过证据。

Implementation Checklist：四页布局、子页字体间距、非空数据、功能跳转、发布链接顺序、审批签认表单、发现项修复、最终原生重拍和同图对照均完成。完整业务规则与验证范围见 [验收说明](qa/business-followups-20261019/验收说明.md)。
