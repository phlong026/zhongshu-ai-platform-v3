# 业务页面追加反馈视觉验收 · 2026-10-20

final result: passed

本轮是现有业务小程序的定向修改。沿用米白背景、棕色操作、系统无衬线字体；首页统计卡片、设计首图、二创生成状态和业务个人页遵循用户最新反馈。上一轮记录已保存在 [before/design-qa.md](qa/business-refinements-20261019/before/design-qa.md)。

## 来源与同图对照

| 页面 | source visual truth path | implementation screenshot path | 对照 |
| --- | --- | --- | --- |
| 首页 | `效果图/业务人员四页改版方案_20261019/02_首页_方案2.png`，并结合本轮三卡/人数单位/取消演示标识的要求 | `qa/business-refinements-20261019/native/home-top.png` | [完整](qa/business-refinements-20261019/comparisons/home.png) · [局部](qa/business-refinements-20261019/comparisons/home-focus.png) |
| 设计 | `效果图/创作与设计页面第二轮方案_20261019/04_AI设计_方案1.png`，保留此前确认的四入口 | `qa/business-refinements-20261019/native/design-top.png` | [完整](qa/business-refinements-20261019/comparisons/design.png) · [首图局部](qa/business-refinements-20261019/comparisons/design-focus.png) |
| 二创 | 项目现有二创组件及本轮明确的空结果→生成→AI优化交互；上一轮原生 `qa/business-followups-20261019/final-native/studio.png` | `qa/business-refinements-20261019/native/rewrite-final-generated.png` | [状态对照](qa/business-refinements-20261019/comparisons/rewrite.png) |
| 业务我的 | 项目现有暖色个人信息卡与业务页视觉体系，本轮按业务内容调整，不采用工程或老板岗位内容 | `qa/business-refinements-20261019/native/profile-sales-top.png` | [专员/负责人及底部](qa/business-refinements-20261019/comparisons/profile.png) |
| 独立设计功能 | 既有业务表单体系及对标仓库的直接功能入口 | `qa/business-refinements-20261019/native/polish-entry-ai-config.png` | [四入口最终页面](qa/business-refinements-20261019/comparisons/entries.png) |

viewport：微信开发者工具 iPhone 12/13 (Pro)，逻辑窗口390×844，状态区47，pixelRatio3。选定效果图853×1844；SDK截图353×762。比较图统一为390px宽，保留纵横比；[manifest](qa/business-refinements-20261019/comparisons/manifest.json) 记录每张图的来源和原始像素。系统状态条/设备边框不作为应用视觉差异。

state：使用业务兼岗人员切换业务员和负责人。首页负责人初始可见客户5人、今日待跟进2人、预约到店1人；专员只显示本人授权记录。原生流程批次包含方案保存和角色切换，因此个人页后续待跟进数可能随实际操作变化，未将效果图数字硬编码。最终表单样式以 `polish-entry-*` 为准；较早的流程截图用于业务行为证据。

## Findings 与修复历史

- **[P1，已修复] 设计入口全部先跳客户列表。** 现四入口直接进入灵感、AI平面、AI立面、独立预算。选择结果后通过保存面板明确关联客户。原生验证尺寸检索→平面→立面→预算→客户→实测，模型补测老板审批及合同金额绑定。[最终入口](qa/business-refinements-20261019/comparisons/entries.png)、[流程](qa/business-refinements-20261019/comparisons/plan-flow.png)。
- **[P2，已修复] 设计首图52%边界割裂。** 建筑图延伸到文案区域下方，使用暖色透明度过渡，没有竖向硬边；文字和主按钮均保持可读。已打开选定效果图与最新原生完整/局部并排图检查。[首图对照](qa/business-refinements-20261019/comparisons/design.png)。
- **[P2，已修复] 首页统计缺少层次且残留演示标识。** 三张独立卡片采用细边框、轻阴影与暖色重点卡，增加图标、箭头和人数单位；标签及数字跳转符合本轮要求。[统计局部](qa/business-refinements-20261019/comparisons/home-focus.png)。
- **[P2，已修复] 二创未生成已有内容，右上角是编辑。** 初始结果为空，显示待生成提示；生成后启用口播操作，右上角为AI优化，保留原文与优化记录。生成后自动定位，完整结果位于固定标题下方和底栏上方。[最终空/生成/优化状态](qa/business-refinements-20261019/comparisons/rewrite.png)。
- **[P2，已修复] 独立设计选择框原生渲染缺少背景。** 改用明确的选择框容器类名，风格、候选数、清晰度、预算配置与输入框一致。补测验证原生框体高度及真实交互。[修复前后](qa/business-refinements-20261019/comparisons/picker-fix.png)。
- **[P2，已修复] 首次实测面积沿用旧默认值。** 独立预算继续实测时带入本次面积；存在实测资料或人工暂存时保留。模型验证539.4㎡带入、人工改为511.5㎡后重开保留；原生补测独立预算360㎡进入实测仍为360㎡。[补测截图](qa/business-refinements-20261019/native/polish-budget-measurement.png)。

已逐张查看首页/设计源图与原生对照、首图和统计局部、四个最终功能页面、二创三个状态、专员/负责人个人页、方案和预算保存流程，以及最终实测面积。当前业务范围无待修复P0/P1/P2视觉问题。

## 五项验收

| 项目 | 检查结果 |
| --- | --- |
| 字体与层级 | 统一 `-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif`；区块标题32rpx/46rpx、600，正文28rpx/42rpx，辅助24rpx/36rpx。原生首页、设计、业务我的区块标题的字体族、字号、字重、行高完全一致，具体值见 `native/results.json`。数字与“人”分层，长正文可换行。 |
| 间距与布局 | 页面左右28rpx，区块间距24rpx、卡片圆角22rpx；三项人数卡片间隔16rpx。新功能选择框和输入框高度76rpx。原生底部内容可滚动到达，二创固定底栏与结果无遮挡。 |
| 色彩 | 米白底、棕色主操作、灰色辅助文案统一。统计重点卡暖色，其他统计卡白色；禁用口播/优化按钮降低透明度，状态区别清楚。 |
| 图片与图标 | 沿用独立建筑、平面、品牌、图标素材；没有把整张效果图作为页面。首图自然融合，灵感与候选保留模拟说明及参考版本。未声称模拟图具有实际2K/4K像素。 |
| 内容与交互 | 首页非空且数量由授权记录计算；二创空状态是用户要求。灵感、平面、立面、预算内容可操作，客户只在明确保存时绑定。“我的”围绕业务内容，负责人有团队入口，其他岗位分流保留。 |

## 测试范围及取舍

131项模型/页面检查，134个模拟阶段、268个前后状态页面通过。原生8组主批次与5组最终补测，共37张有效截图；两批最终运行异常为0，均恢复测试前存储。独立副本的关键源码与工作区逐字节一致，见 [源码校验](qa/business-refinements-20261019/native/source-manifest.json)。原生实际交互使用渲染控件及Page处理器，没有用模型输出代替截图。

本轮保留项目紧凑品牌栏、已有字体与建筑素材；不恢复参考图中独立装饰光带。统计卡片依用户最新要求加强层次，设计采用四入口替代源图两入口。AI与文案优化使用本地模拟，专业成果仍走设计师及设计负责人流程；未验证外部AI、发布平台或真实付款。未做真机验收。

Implementation Checklist：二创待生成状态、AI优化、结果自动定位、人数卡片及筛选、首图过渡、四独立入口、版本关联保存、预算到实测及老板审批/合同、业务我的内容、字体与间距、P1/P2修复及补测、存储恢复均完成。[完整验收与复测命令](qa/business-refinements-20261019/验收说明.md)。
