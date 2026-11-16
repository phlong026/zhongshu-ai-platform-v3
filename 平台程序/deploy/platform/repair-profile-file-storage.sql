-- psql -v public_origin=https://platform.example.com -f repair-profile-file-storage.sql
-- B 独立数据库的基础文件存储初始化。仅在没有主配置时新增，不覆盖已有 COS/本地/DB 配置。
\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE infra_file_config IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO infra_file_config (id, name, storage, master, config, remark, creator, updater)
SELECT nextval('infra_file_config_seq'), 'B platform profile files', 1, TRUE,
       jsonb_build_object(
           '@class', 'cn.iocoder.yudao.module.infra.framework.file.core.client.db.DBFileClientConfig',
           'domain', :'public_origin')::text,
       'B-owned database storage; initializes missing master without external credentials', 'profile-storage-repair', 'profile-storage-repair'
WHERE NOT EXISTS (SELECT 1 FROM infra_file_config WHERE master=TRUE AND deleted=0);
COMMIT;
SELECT id, storage, master, config::jsonb->>'domain' AS public_domain
FROM infra_file_config WHERE master=TRUE AND deleted=0;
