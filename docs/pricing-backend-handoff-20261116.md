# 统一定价管理后端补充

本次只增加计费目录可见性及调价历史读取，不改登录、不设定运营价格。

## 可评审文件

- `平台程序/platform/platform-wallet/src/main/java/cn/iocoder/yudao/module/platform/wallet/orgpoint/OrgPointPricingController.java`：新增 `GET /admin-api/platform/org-points/price-history?subject=copy`，最多返回最近200条；复用现价接口的权限、总部租户及账号状态校验，包含版本、操作人和原因。无新表，无迁移。
- `平台程序/platform/platform-wallet/src/test/java/cn/iocoder/yudao/module/platform/wallet/orgpoint/OrgPointPricingSecurityTest.java`：5项权限、身份、总部门禁及旧模式隔离测试全部通过。
- 当前共享目录 `BillingPriceCatalog.java` 已由人物库开发补充 `avatar_clone` 与 `voice_clone`，默认价0、版本unconfigured，待运营从定价页设置。发布时需保留这两项以及 `OrgPointService.creditsFor` 对无效/零单价的拒绝逻辑。不要为通过验收随意填写生产价格。

## 前端

独立入口 `/admin/platform-pricing`，从公司积分池中独立出来，无需选择公司。按权限显示菜单，读取原有 `/platform/org-points/prices`，发布仍用已有幂等接口，复用原租户/账号隔离的待确认请求记录。

前端已兼容旧服务：没有克隆科目时显示“待服务发布”并禁用设置，不伪装为可用配置。调价历史接口未发布时显示明确加载错误。

## 发布边界

登录会话先完成发布，本次在其线上包基础上追加定价补充，保留登录修复、组织管理、密码重置及直接加分逻辑。没有以本地旧分支整包覆盖。

已发布到B服务：`platform-pricing-20261116-6aaab00c.jar`，基线为 `platform-login-20261116-414c13d4.jar`。仅修改钱包模块内5个定价类文件，其余归档内容逐项校验相同；无新增数据库迁移。服务健康UP，线上目录13项，历史查询可读取。A服务未变更。

后端验证共15项通过：权限门禁5项、价格与历史数据库查询2项、现有定价集成8项。数据库测试使用独立临时PostgreSQL，不修改生产价格。发布证据位于主工作区 `docs/admin-pricing-audit-20261116/backend-release.json`。
