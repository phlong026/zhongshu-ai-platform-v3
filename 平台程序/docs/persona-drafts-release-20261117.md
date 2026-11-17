# 人物定位自动保存部署（2026-11-17）

后端代码`2f90f66d`已提交并推送，前端代码`dbff62f8`；微信版本`1.0.20261117.1241`已通过官方工具上传，体验版切换待用户确认。真机和真实媒体制作测试暂停，未提审或正式发布。

内容模块169项测试通过，包含真实隔离PostgreSQL、草稿权限、并发CAS、确认事务和幂等重放；前端52项专项及基于1159的候选包全量检查通过。

B服务只增量更新内容模块相关类和迁移文件，保留其他运行模块。当前Jar SHA-256：`b47e44a91bae88da3df36fed3f8228a01f5c25a802622db2461feed028e84602`。945迁移已完成；应用账号通过INSERT/UPDATE/SELECT事务验证后回滚测试数据，健康UP，公开登录策略code0，匿名草稿访问code401。

本机systemd部署的迁移必须显式使用`--spring.config.location=file:/etc/zhongshu-platform-b-integration/migration.properties`，避免默认application-migration.properties加载容器专用/run/secrets目录。数据库owner不具备SET ROLE platform_app权限，探针应使用应用账号自身连接。账号凭据只在进程内使用，未进入发布回执或仓库。

完整私有备份、兼容回滚Jar与回执位于服务器`/tmp/persona-drafts-release-20261117-r4/`。兼容回滚Jar保留945迁移资源和数据库草稿，不删除用户内容或修改Flyway历史。
