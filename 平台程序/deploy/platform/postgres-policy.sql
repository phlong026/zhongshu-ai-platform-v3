-- Run as platform_owner after migration or restore, before API startup.
\set ON_ERROR_STOP on
BEGIN;
DO $$
DECLARE
    item record;
BEGIN
    IF current_database() <> 'zhongshu_platform_b' OR current_user <> 'platform_owner' THEN
        RAISE EXCEPTION 'B database / migration-owner identity mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('platform_owner', 'platform_app')
               AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
        RAISE EXCEPTION 'B roles have privileged attributes';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = 'platform_app'::regrole) THEN
        RAISE EXCEPTION 'Application role must not inherit another role';
    END IF;
    IF has_database_privilege('platform_app', current_database(), 'CREATE')
       OR has_database_privilege('platform_app', current_database(), 'TEMPORARY')
       OR EXISTS (SELECT 1 FROM pg_database WHERE datallowconn AND datname <> current_database()
                  AND has_database_privilege('platform_app', oid, 'CONNECT')) THEN
        RAISE EXCEPTION 'Application database privileges exceed B CONNECT';
    END IF;
    IF NOT has_schema_privilege('platform_app', 'public', 'USAGE')
       OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege('platform_app', oid, 'CREATE')) THEN
        RAISE EXCEPTION 'Application schema policy mismatch';
    END IF;
    FOR item IN SELECT c.relname, c.relkind, c.relowner FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S') LOOP
        IF item.relowner <> 'platform_owner'::regrole THEN
            RAISE EXCEPTION 'Object % must belong to platform_owner', item.relname;
        END IF;
        IF item.relname = 'flyway_schema_history' THEN
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM platform_app, PUBLIC', item.relname);
            IF has_table_privilege('platform_app', 'public.flyway_schema_history', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
                RAISE EXCEPTION 'Flyway history must be owner-only';
            END IF;
        ELSIF item.relkind = 'S' THEN
            IF NOT has_sequence_privilege('platform_app', format('public.%I', item.relname), 'USAGE')
               OR has_sequence_privilege('platform_app', format('public.%I', item.relname), 'UPDATE') THEN
                RAISE EXCEPTION 'Sequence privilege mismatch: %', item.relname;
            END IF;
        ELSE
            IF NOT (has_table_privilege('platform_app', format('public.%I', item.relname), 'SELECT')
                    AND has_table_privilege('platform_app', format('public.%I', item.relname), 'INSERT')
                    AND has_table_privilege('platform_app', format('public.%I', item.relname), 'UPDATE')
                    AND has_table_privilege('platform_app', format('public.%I', item.relname), 'DELETE'))
               OR has_table_privilege('platform_app', format('public.%I', item.relname), 'TRUNCATE,REFERENCES,TRIGGER') THEN
                RAISE EXCEPTION 'DML privilege mismatch: %', item.relname;
            END IF;
        END IF;
    END LOOP;
END $$;
COMMIT;
