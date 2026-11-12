#!/bin/sh
# Official entrypoint executes this file on a new volume; an existing volume is never reset.
set -eu
umask 077
export PLATFORM_OWNER_PASSWORD="$(cat /run/secrets/pg_owner_password)"
export PLATFORM_APP_PASSWORD="$(cat /run/secrets/pg_app_password)"
if [ "${#PLATFORM_OWNER_PASSWORD}" -lt 32 ] || [ "${#PLATFORM_APP_PASSWORD}" -lt 32 ] || [ "$PLATFORM_OWNER_PASSWORD" = "$PLATFORM_APP_PASSWORD" ]; then
    printf '%s\n' 'ERROR: distinct external database credentials (32+ chars) required' >&2
    exit 1
fi
# psql reads the environment internally; passwords are not command-line arguments or logs.
psql -X --username postgres --dbname zhongshu_platform_b --set ON_ERROR_STOP=1 <<'SQL'
\getenv owner_password PLATFORM_OWNER_PASSWORD
\getenv app_password PLATFORM_APP_PASSWORD
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
CREATE ROLE platform_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'owner_password';
CREATE ROLE platform_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'app_password';
ALTER DATABASE zhongshu_platform_b OWNER TO platform_owner;
REVOKE ALL ON DATABASE zhongshu_platform_b FROM PUBLIC;
GRANT CONNECT ON DATABASE zhongshu_platform_b TO platform_app;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO platform_owner;
GRANT USAGE ON SCHEMA public TO platform_app;
ALTER DEFAULT PRIVILEGES FOR ROLE platform_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO platform_app;
ALTER DEFAULT PRIVILEGES FOR ROLE platform_owner IN SCHEMA public GRANT USAGE ON SEQUENCES TO platform_app;
ALTER DEFAULT PRIVILEGES FOR ROLE platform_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER ROLE platform_app IN DATABASE zhongshu_platform_b SET search_path = public;
ALTER ROLE platform_owner IN DATABASE zhongshu_platform_b SET search_path = public;
SQL
unset PLATFORM_OWNER_PASSWORD PLATFORM_APP_PASSWORD
