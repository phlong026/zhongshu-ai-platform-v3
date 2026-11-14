> 以下保留开发通道提交前的验证记录；主代理最终提交及交付状态见13增量报告。

# CONTENT-01 手动发布登记后端验证报告

## 范围与结论

- 工作树：`/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/.worktrees/joint-review-20261114/平台程序`
- 基线 HEAD：`7f46055709a518d42b02360d858c8f5d9bf3773a`，分支 `fix/joint-release-split-20261114`
- 结论：新建发布记录只允许手动登记；非空 `scheduledAt` 在任何查询、幂等读取或写入前被拒绝。本人仅能登记本人作品，仅能变更本人发布记录。首发平台值仅接受 `douyin|channels`。
- 未执行提交、推送、部署或生产写入。

## 实现要点

- `ContentPublicationService.createFromPost/create`：最前置拒绝非空定时值；`accountId` trim 后必须 1..64，超长不截断；平台 allowlist；核验 `work.authorUserId == userId`；新记录固定 `publishing` 且 `scheduled_at IS NULL`。
- `createFromPost` 保留原有 CommandLog 幂等语义：同键同载荷返回原记录，同键异载荷拒绝；创建与 command 记录仍处于 `@Transactional(rollbackFor = Exception.class)` 边界。
- 历史 `scheduled` 记录不自动执行；本人可显式 `verify -> link`。数据库 `ck_fr_content_publication_schedule` 要求非 `scheduled` 时主列为 NULL，因此转换时清主列，但将原时间保留在现有 `versions.originalScheduledAt`；后续版本更新继续保留，原 `signature` 不更改。无 schema/migration 变更。
- Controller view 新增 `authorUserId`/`publishText`/`tags`/`createTime` ISO 字段；创建响应使用同一显式时间写库并返回，不再返回空 `createTime`。
- 接口文案明确这是手动登记，不代表外部账号授权或自动发帖。

## RED 证据

1. 原定时/账号行为复现：

   `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./mvnw -Dsurefire.failIfNoSpecifiedTests=false -pl domains/franchise/franchise-content -am -Dtest=ContentPublicationServiceTest,ContentPublicationHttpContractTest test`

   退出码 1；5 tests，4 failures：`createFromPost` 未拒绝定时、`create` 未拒绝定时、65 字符账号未拒绝、HTTP 定时请求返回成功码。

2. 真库 createTime/对象归属复现：

   `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -DsocksNonProxyHosts='localhost|127.*|[::1]' -Dhttp.nonProxyHosts='localhost|127.*|[::1]' -Dsurefire.failIfNoSpecifiedTests=false -pl domains/franchise/franchise-content -am -Dtest=ContentPublicationServiceTest,ContentPublicationHttpContractTest,ContentPublicationPgIntegrationTest test`

   退出码 1；11 tests，3 failures：PG 创建结果 `createTime` 为空，同组织同事作品可被登记，HTTP 创建响应 `createTime` 为空。

注：RED 为实际控制台证据，未另存原始日志，未补造失败记录。

## GREEN 证据

### 定向新行为 + PostgreSQL 真库

`JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -DsocksNonProxyHosts='localhost|127.*|[::1]' -Dhttp.nonProxyHosts='localhost|127.*|[::1]' -Dsurefire.failIfNoSpecifiedTests=false -pl domains/franchise/franchise-content -am -Dtest=ContentPublicationServiceTest,ContentPublicationHttpContractTest,ContentPublicationPgIntegrationTest test`

- 退出码 0；12 tests，12 passed，0 failures/errors/skips；29/29 reactor modules success。
- PG 真库覆盖：本人创建手动记录；同键同载荷重放；同键异载荷拒绝；定时值/非法平台零 publication 且零 command 写入；同组织同事及跨组织作品拒绝；本人 `verify/link`；重复 verify/link；历史 scheduled 人工登记；越权无变更；原计划时间在 versions 可追溯且 signature 不变。
- 日志：`/tmp/zs-manual-publication-targeted-green.log`

### 全 content 及必要上游

`JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -DsocksNonProxyHosts='localhost|127.*|[::1]' -Dhttp.nonProxyHosts='localhost|127.*|[::1]' -pl domains/franchise/franchise-content -am test`

- 退出码 0；29/29 reactor modules success；1122 tests，0 failures，0 errors，18 skipped。`franchise-content` 自身 64 tests 全通过。
- 18 skipped 为原有外部前置/显式禁用：FTP 1、S3 6、SFTP 1、Local file 2、SMS client 7、Mail sender 1；与本次 content 改动无关。
- 日志：`/tmp/zs-manual-publication-content-final.log`

## 打包与实际制品扫描

1. B 候选制品重打包：

   `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./mvnw -pl yudao-platform-server -am -DskipTests package`

   退出码 0；40/40 reactor modules success；日志 `/tmp/zs-manual-publication-package.log`。此命令仅负责打包，不把 skipTests 声称为测试通过。

2. 实际 JAR 扫描：

   `python3 -I script/platform/verify-platform-artifact.py yudao-platform-server/target/yudao-platform-server.jar`

   退出码 0；已对实际 executable JAR 校验 Start-Class、B 应用类、环境守卫、runtime 依赖边界、Flyway location 白名单/版本唯一性并输出 migration manifest。日志 `/tmp/zs-manual-publication-artifact-verifier.log`。

3. Verifier 自测：`python3 -I script/platform/test_artifact_verifier.py` → 6/6 passed。

4. 制品：`yudao-platform-server/target/yudao-platform-server.jar`，182,691,458 bytes，SHA-256 `cbb702707ebb2a5f0bdb18448f49bb34016af239752eb37cde2140b50239779c`。

## 静态、安全与自审

- `git diff --check -- domains/franchise/franchise-content` 无输出；对未跟踪测试文件另行扫描行尾空白，无命中。
- 对全部本次 content 文件扫描 password/secret/api-key/token/private-key 模式，无命中；仅新增对仓库既有test-support模块的测试依赖，没有新增第三方依赖或 schema。
- 授权信心为 high：服务端同时校验 tenant/company 与 author user，并以同组织同事/跨组织负例真库覆盖，符合 OWASP 要求对每次对象访问做后端授权：https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- JDBC/事务信心为 high：复用现有 `JdbcTemplate` 参数化 update 及 Spring 声明式事务，真 PostgreSQL 覆盖了成功、唯一冲突、零写入拒绝及重放。官方依据：https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html 与 https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- 回滚性：clean。仅 Java/POM/测试变更，无 migration 或外部副作用；主代理后续可以单独提交并用 `git revert` 撤回。

## 变更文件

- `domains/franchise/franchise-content/pom.xml`
- `domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/content/ContentPublicationService.java`
- `domains/franchise/franchise-content/src/main/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationController.java`
- `domains/franchise/franchise-content/src/test/java/cn/iocoder/yudao/module/franchise/content/content/ContentPublicationServiceTest.java`
- `domains/franchise/franchise-content/src/test/java/cn/iocoder/yudao/module/franchise/content/content/ContentPublicationPgIntegrationTest.java`
- `domains/franchise/franchise-content/src/test/java/cn/iocoder/yudao/module/franchise/content/controller/admin/ContentPublicationHttpContractTest.java`

## 未验证项

- 未进行生产部署、真实外部平台授权/发帖（本功能明确不提供这些能力）、真机角色 UAT 或本轮全 40 模块不跳测试重跑。
- 本轮完整测试边界是 29 模块 content 及必要上游，不声称早先的全平台账本数为本轮重跑。
