# 设计岗位退役的升级兼容与备份恢复

适用 BIZ-T12：`hq-designer`、`hq-design-lead`、`hq-design-admin`。本流程沿用现有冻结、备份及隔离恢复机制，不改已应用的 1200/1201 SQL，不删除任职历史，不重新授予任何任职。

## 已验证的问题与修复边界

原 1200 对有存量任职的数据库有两处阻断：702 的状态约束只允许 `ACTIVE / REVOKED`，写入 `INACTIVE` 会被拒绝；即使先撤销任职，删除模板仍会违反 `fk_pf_staff_post_role`。空库迁移不能验证这两种情况。

项目已有 Flyway 在 `db/callback` 自动发现 Java callback 的机制。`DesignPostRetirementCompatibility` 只在 Flyway 即将执行原 1200 时运行；已成功执行的 1200 不会重放。它复用新增 1211 的 SQL，在检测到三岗存量任职后验证原约束，随后在同一事务中：

1. 将仍为 `ACTIVE` 的三岗任职按既有规则记为 `REVOKED`，填入撤销时间及系统操作者 `0`。原已撤销行及其他岗位保持原值。
2. 暂卸角色模板外键，删除三岗模板，再以原定义和 `NOT VALID` 重新建立外键。此标记只免除历史行的回扫，新的任职插入及 `role_code` 变更仍受外键约束。
3. 原 1200 在同一迁移过程中继续执行；新增 1211 随后记录同一幂等收敛。

生产显式 `PlatformMigrationConfiguration` 与 Spring 自动 Flyway 均通过标准扫描发现同一 callback。正常 API 启动仍禁止自动迁移。不要绕开 Flyway 单独执行旧 1200；不要为消除 `NOT VALID` 标记而删除历史任职或运行 `VALIDATE CONSTRAINT`。未知约束定义会被拒绝，须先核清实际部署结构。

## 升级前的备份依据

按 [现有部署说明](README.md#旧-api-首次升级与恢复)冻结 B 写入、回调和任务。普通独立部署使用已有 `ops.py backup`；完整同机部署使用 [完整备份及恢复流程](CAPABILITY_DEPLOYMENT.md)，不得用部分备份替代。核验可信 manifest SHA256 和备份文件 hash，并保留对应的旧镜像摘要。

恢复角色前，先把这份**退役前**的 `postgres.dump`（完整备份中为 `platform-postgres.dump`）按原流程恢复到隔离空库。核对备份库中 `flyway_schema_history` 尚无成功的 1200/1201，且真实角色、菜单和用户绑定与部署前记录一致。没有可核实的退役前备份时，不能从固定种子 ID 或当前空表推测原授权；两个 undo 会拒绝缺少快照的调用。

本轮代码和隔离测试不证明目标环境已存在该备份，也不代表已执行目标环境恢复。

## 从已核验隔离库提取原行

在仓库外的受控恢复目录操作。下面的 `\copy` 在连接到**已恢复的退役前隔离库**的 `psql -X -v ON_ERROR_STOP=1` 会话中执行。完整保留列、原 ID、tenant_id、自定义权限范围和历史状态；不要筛掉已停用或软删除的原行。

```sql
SELECT version, success FROM flyway_schema_history
WHERE version IN ('20261127.1200', '20261127.1201');

\copy (SELECT * FROM system_role WHERE code IN ('hq-designer','hq-design-lead','hq-design-admin') ORDER BY id) TO 'roles.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT rm.* FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE r.code IN ('hq-designer','hq-design-lead','hq-design-admin') ORDER BY rm.id) TO 'role-menus.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT ur.* FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id WHERE r.code IN ('hq-designer','hq-design-lead','hq-design-admin') ORDER BY ur.id) TO 'user-roles.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT * FROM pf_post_template WHERE domain='franchise' AND role_code IN ('hq-designer','hq-design-lead','hq-design-admin') ORDER BY role_code) TO 'post-templates.csv' WITH (FORMAT csv, HEADER true)
```

记录这四个文件的 SHA256、行数、来源备份 manifest SHA256 和恢复目标。CSV 包含授权资料，不提交到代码仓库。模板和角色若在备份中本来不存在，对应文件应为空数据集，不能补造种子。原绑定若有租户错配、用户/菜单缺失或目标已有身份冲突，先核查原因，不能用 `ON CONFLICT DO NOTHING` 掩盖。

## 在冻结的恢复目标加载并恢复

在受控连接中保持 `ON_ERROR_STOP=1`。将 psql 变量 `undo_roles`、`undo_templates` 分别设置为本次已核验制品中 1201、1200 undo 的绝对文件路径（均在 `domains/franchise/franchise-org/src/main/resources/db/migration-undo/` 下）。先确认当前表结构与备份提取列兼容，再加载四张会话临时表；连接关闭后这些表自动消失。

```sql
CREATE TEMP TABLE retired_design_role_backup (LIKE system_role INCLUDING ALL);
CREATE TEMP TABLE retired_design_role_menu_backup (LIKE system_role_menu INCLUDING ALL);
CREATE TEMP TABLE retired_design_user_role_backup (LIKE system_user_role INCLUDING ALL);
CREATE TEMP TABLE retired_design_post_template_backup (LIKE pf_post_template INCLUDING ALL);
\copy retired_design_role_backup FROM 'roles.csv' WITH (FORMAT csv, HEADER true)
\copy retired_design_role_menu_backup FROM 'role-menus.csv' WITH (FORMAT csv, HEADER true)
\copy retired_design_user_role_backup FROM 'user-roles.csv' WITH (FORMAT csv, HEADER true)
\copy retired_design_post_template_backup FROM 'post-templates.csv' WITH (FORMAT csv, HEADER true)

BEGIN ISOLATION LEVEL READ COMMITTED;
\i :undo_roles
\i :undo_templates
COMMIT;
```

两个文件必须在同一会话、同一事务中执行；变量缺失、文件缺失或 SQL 错误时，`ON_ERROR_STOP=1` 应终止会话并回滚未提交事务。缺少任一备份、目标角色 ID 被其他身份占用、已有同租户同角色编码冲突、绑定租户错配或原绑定 ID 冲突时，必须回滚整组；不得覆盖其他岗位。完全相同的原行已经恢复时重复执行不会再增加角色或绑定。

两个 undo 都不更新 `pf_staff_post`。已撤任职仍为 `REVOKED`，不会凭角色或菜单恢复自动获得可用岗位。恢复仅用于经核准的故障回滚；正常退役环境不执行 undo。

## 验收证据

- 比对三岗在每个真实 tenant 下的原 ID、所有角色字段、菜单及用户绑定；其他角色逐行不变。
- 核对单非 0 租户、多个租户、自定义 ID、固定旧种子 ID 被其他岗位占用、重复恢复和冲突拒绝。
- 核对任职行数及原撤销时间不丢失，新任职/改为未知角色仍被数据库外键拒绝。
- 恢复应用前按既有部署流程核对权限缓存和旧会话，实际请求仍必须经过当前任职检查；不要通过恢复旧缓存重授设计岗位。
- 存量委托承接和可办理待办与 BIZ-T13 联验；本脚本不改委托、项目、资金或工程状态。

`DesignPostRetirementPgIntegrationTest` 使用真实 PostgreSQL、原始 702 约束和实际 forward/undo 文件覆盖上述代码边界。目标环境的备份证据、账号请求及完整回滚仍须单独记录，不能用测试通过代替。
