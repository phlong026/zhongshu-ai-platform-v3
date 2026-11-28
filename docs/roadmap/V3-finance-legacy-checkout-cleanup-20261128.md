# L04：财务与老板模块旧设计费模拟收银代码清理

日期：2026-11-28

## 清理范围

本次只清理已经退出当前生产流程、没有活动页面消费者的旧本地设计费模拟收银实现，不改真实财务账本、历史模拟订单、历史回执和当前设计委托读取能力。

### 删除的生产代码
- `miniprogram/utils/design-checkout.js`：旧本地演示计价、订单创建、模拟支付、支付成功判定。其写入口 `create()` / `pay()` 已永久抛出停用异常，`savePricing()` 仍写本地管理存储，属于旧流程残留。
- 当前工作流入口 `miniprogram/pages/workflow/commission/index.js`、`external-entry.js`、`presentation.js`、`design-brief.js` 未导入原写入型工具；新设计委托使用 API 与服务端结算适配器。
- 复核发现 7 个历史读模型仍需读取旧本地模拟订单（委托历史、旧设计工作台/通知、旧财务账本）。因此没有粗暴删除其兼容能力，而是将其替换为 `utils/design-checkout-history.js`，仅保留 `is/order/pricing/authorized/paid` 只读接口。
- 新适配器不导出 `savePricing/quote/create/pay`，不能再写本地价格、创建模拟订单或推进模拟付款。
- 当前 `simulated-payment.wxml` 仍展示服务端历史回执，不依赖本地 checkout 工具，继续保留。

### 删除的退役测试和命令
- `verify-design-checkout.cjs`、`verify-design-checkout-pages.cjs`、`verify-design-journey.cjs`、`verify-design-payment-finance-copy.cjs`：仅打印 retired 并退出 0，不执行断言，不能算有效覆盖。
- `verify-design-checkout-native.cjs`、`verify-design-journey-native.cjs`：依赖 2026-10 的外部 native-project 和本地演示流程，测试对象已退役，不再作为当前项目验收。
- 删除 package.json 中 `check:design-checkout`、`check:design-checkout:native`、`check:design-journey`、`check:design-journey:native` 四个无效命令。

## 明确保留

- 服务端 `CommissionSimulatedPayments` 的历史订单只读查询和旧成功回执恢复。
- `simulated-payment.wxml` 的历史模拟记录展示。
- 财务模块的正式线下付款、总部核收、退款、凭证、幂等和历史结算复核。
- `DesignFeeSettlementAdapter` 的 FULL / FIRST / TAIL 真实应付与收付净额计算。
- 历史 QA 截图和原生项目快照作为审计资料，不进入小程序生产包。

## 验收标准

1. 当前生产页面不再引用原写入型 `utils/design-checkout.js`；历史读模型统一引用只读 `design-checkout-history.js`。
2. 当前 package scripts 不再暴露退役的 checkout/journey 检查命令，read-only adapter regression 已进入 check:business-repair。
3. 当前主检查 `npm run check`、`npm run check:finance-workbench`、`npm run check:design-ui` 通过。
4. 服务端历史模拟回执和正式财务流水页面仍可查询；不得因删除旧本地工具删除数据库记录。
5. 不修改任何生产历史数据，不自动把模拟订单转换成真实收付款。
