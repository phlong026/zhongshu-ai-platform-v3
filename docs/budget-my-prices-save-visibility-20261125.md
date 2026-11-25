# 快速预算「我的当地单价」：新建城市改价后不显示已保存（2026-11-25 修复）

## 现象

在小程序「快速预算」里选择建造地区，基于模板库中的武汉市新建一个城市（自定义地区），进入「我的当地单价」调整当地价格；保存提示成功，但页面上看不到已保存的价：仍显示武汉基准价、按钮仍是「调整」、已改项计数为 0。再次点开输入框，回填的是武汉基准价；若直接再保存，会把刚填的当地价覆盖回武汉价。用户感知为「改了价格保存不了」。

## 根因

服务端对两类地区返回两套价格来源：

| 地区类型 | 价格来源枚举 |
| --- | --- |
| 平台地区（如武汉） | `ACCOUNT_OVERRIDE` / `DEFAULT` / `MISSING` |
| 本人自定义地区（新建城市） | `PROFILE_COPIED` / `PROFILE_COEFFICIENT` / `PROFILE_MANUAL` / `WUHAN_FALLBACK` / `MISSING` |

`pages/budget/prices.js` 的 `toView()` 只把 `ACCOUNT_OVERRIDE` 认作「我的价」，于是：

1. `option.overridden` 恒为 `false`，`prices.wxml` 中「我的单价」整行不渲染；
2. `overriddenCount` 恒为 0，顶部「已启用 N 项当地单价」不出现；
3. `startEdit()` 因 `overridden` 为 false 回填基准价（武汉价），而不是已保存配置价；
4. 再次保存即把 `PROFILE_MANUAL` 手工价改成武汉价。

数据本身没有丢：自定义地区的价格写在 `budget_account_price`（`region_scope='USER'`，`region_code=<城市 code>`），预算测算由 `BudgetPriceResolver.customPrice()` 优先读取这些行，金额是对的；问题在展示与回填口径。

## 修复

- `prices.js`：新增 `hasMyPrice()`，`ACCOUNT_OVERRIDE` 与三种 `PROFILE_*` 都视为「我的价」；输出 `sourceLabel`（手工价 / 系数价 / 武汉复制）与 `canReset`。
- `prices.js`：编辑回填改为「有我的价就用我的价，没有才用基准价」。
- `prices.wxml`：「我的单价」后显示来源标签；「恢复默认」按钮改为只在 `canReset`（账号覆盖价 / 手工价）时出现，避免对武汉复制行做无意义的恢复。
- `prices.js`：自定义地区的恢复默认提示改为「按武汉基准价（含地区系数）重新计算该项」，与 `BudgetPriceProfileService.resetOne` 实际行为一致。
- `prices.scss` / `prices.wxss`：新增 `.price-source` 标签样式。

## 验证

- 新增 `scripts/verify-budget-my-prices.cjs`（6 组，纳入 `npm run check` 与 `npm run check:budget-prices`）：
  1. 新建城市下武汉复制/系数/手工价都显示为「我的单价」，缺价项保持待补；
  2. 再次编辑回填已保存的当地价而不是武汉基准价；
  3. 保存后重新读取立即显示「我的单价」，计数、按钮、toast 正确；
  4. 平台地区账号覆盖价行为不回退；
  5. 恢复默认走自定义地区重置接口，提示文案随地区类型变化；
  6. 换会话后不再提交保存。
- `node scripts/verify-wxml-complete.cjs`：113 个 WXML 通过。
- `node scripts/verify.cjs`、`node --test scripts/verify-design-ui.cjs scripts/verify-package-budget.cjs`、`node scripts/verify-design-package.cjs` 全部通过。

## 配套后端调整（2026-11-25，已改）

`AppAccountPriceController` 原来的限流是 `budget-my-price` 桶 **10 次/60 秒**（Redisson 令牌桶：突发 10 次后每 6 秒补 1 个），且 PUT 与 DELETE 共用；IP 桶为 30 次/60 秒。新城市标准项约 35 个，快速逐项配置时后半段会返回 429「请求过于频繁，请稍后重试」，前端表现为「保存失败」。

本次调整（文件：`平台程序/yudao-module-design/.../controller/app/AppAccountPriceController.java`）：

| 位置 | 原值 | 现值 |
| --- | --- | --- |
| `setMyPrice` 的 `@RateLimiter`（IP 桶） | 30 次/60 秒 | **120 次/60 秒** |
| `resetMyPrice` 的 `@RateLimiter`（IP 桶） | 30 次/60 秒 | **120 次/60 秒** |
| `setMyPrice` 的账号桶 | `budget-my-price` 10 次/60 秒 | **`budget-my-price` 60 次/60 秒** |
| `resetMyPrice` 的账号桶 | 与改价共用 | **`budget-my-price-reset` 30 次/60 秒**（独立桶） |

校验：单文件 `javac --release 17` 编译通过（0 错误 0 警告，产出 `AppAccountPriceController.class`）；新增 `AppAccountPriceRateLimitTest`（3 项）锁定阈值、时间单位和桶名，实际执行 3/3 通过，并做了变异验证（把账号桶改回 10 次、IP 桶改回 30 次时 2 项失败），说明断言有效。后端其余源码未改，既有合同测试里的 `verifiedAccountRateLimiter` 是 mock，不受阈值变化影响。**该改动需重新构建并部署后端才生效。**

后续迭代仍建议做"整份保存"：小程序改用 `PUT /budget/my-regions/{code}/prices`，一次事务提交 35 项、`price_version` 只 +1、审计一条，从根上不再依赖限流阈值。

管理后台的「基准价格」是平台口径（武汉基准价），与用户自己城市的当地单价是两套数据，本次修改不影响平台基准价。
