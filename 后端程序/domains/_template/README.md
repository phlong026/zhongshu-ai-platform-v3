# 新建业务域模板

本目录不参与构建，只是新建业务域时的照抄清单。以加盟协同域 `franchise` 为参照。

## 1. 先定这几项

| 项 | 规则 | franchise 的取值 |
|---|---|---|
| 域编码 | 小写英文，全局唯一，下文记作 `<域>` | `franchise` |
| 表前缀 | 2–4 个小写字母加下划线，全局唯一 | `fr_` |
| 迁移段号 | Flyway 版本为 `V<日期>.<段号>__<说明>.sql`，每层各占一段，避免并行开发撞号 | 平台能力层 `7xx`；加盟协同域 `8xx`；下一个业务域 `9xx` |
| 一级菜单 | 后台左侧一个一级菜单 | 加盟协同 |

## 2. 后端

1. 新建 `domains/<域>/pom.xml`（聚合，`packaging=pom`），并在 `domains/pom.xml` 的 `<modules>` 加入 `<域>`。
2. 新建 `domains/<域>/<域>-api/`：本域对其他业务域公开的接口与 DTO，参照 `franchise/franchise-api/pom.xml`。
3. 每条业务线一个模块 `domains/<域>/<域>-<业务线>/`：
   - `artifactId`：`yudao-domain-<域>-<业务线>`
   - Java 包：`cn.iocoder.yudao.module.<域>.<业务线>`
   - 只依赖平台能力层模块、框架 starter 和其他域的 `-api`；不依赖其他域的实现模块。
4. 数据访问一律用 MyBatis Mapper（`BaseMapperX`），**不用 `JdbcTemplate` 手写 SQL**，否则组织范围的数据权限拦截器不生效。
5. 每张业务表必须有 `company_id`（归属组织），按团队隔离的再加 `team_id`。
6. 迁移脚本放 `src/main/resources/db/migration/<域>/`，回滚脚本放 `db/migration-undo/<域>/`，并把 `classpath:db/migration/<域>` 加到 `yudao-server` 的 `application-pg.yaml` 的 `flyway.locations`。
7. 接口路径：Web 端 `/admin-api/<域>/...`，小程序端 `/app-api/<域>/...`。
8. 权限码：`<域>:<资源>:<动作>`，由本域迁移脚本写入菜单、按钮和本域岗位模板（角色）。
9. 按需实现平台扩展接口：待办 `TodoProvider`、审批 `ApprovalProvider`、对象授权 `ObjectAccessPolicy`（M1 起由平台层提供）。
10. 在 `yudao-server/pom.xml` 引入本域模块即启用；不引入则菜单、接口都不存在。

## 3. 管理后台

| 放什么 | 位置 |
|---|---|
| 页面 | `管理后台/src/views/<域>/` |
| 接口封装 | `管理后台/src/api/<域>/` |
| 静态路由（如有） | `管理后台/src/router/modules/<域>.ts` |

## 4. 测试（必须）

- 每个写操作至少有三条越权用例：其他组织的人、同组织其他团队的人、申请人自己审批或复核。
- 规则来自前端已有逻辑时，把前端的检查用例改写成后端接口测试，作为验收标准。

## 5. 完成检查

- [ ] `mvn -DskipTests package` 通过
- [ ] 本域迁移在空库上能从头执行，回滚脚本可用
- [ ] 后台以本域岗位登录，只看到本域菜单和本组织数据
- [ ] 越权用例全部通过
