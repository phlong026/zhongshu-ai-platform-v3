#!/bin/sh
set -eu
umask 077
export DESIGN_OWNER_PASSWORD="$(cat /run/secrets/design_pg_owner_password)"
export DESIGN_APP_PASSWORD="$(cat /run/secrets/design_pg_app_password)"
if [ "${#DESIGN_OWNER_PASSWORD}" -lt 32 ] || [ "${#DESIGN_APP_PASSWORD}" -lt 32 ] || [ "$DESIGN_OWNER_PASSWORD" = "$DESIGN_APP_PASSWORD" ]; then
    printf '%s\n' 'ERROR: distinct design database credentials (32+ chars) required' >&2
    exit 1
fi
psql -X --username postgres --dbname zhongshu_design_provider --set ON_ERROR_STOP=1 <<'SQL'
\getenv owner_password DESIGN_OWNER_PASSWORD
\getenv app_password DESIGN_APP_PASSWORD
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
CREATE ROLE design_provider_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'owner_password';
CREATE ROLE design_provider_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'app_password';
ALTER DATABASE zhongshu_design_provider OWNER TO design_provider_owner;
REVOKE ALL ON DATABASE zhongshu_design_provider FROM PUBLIC;
GRANT CONNECT ON DATABASE zhongshu_design_provider TO design_provider_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO design_provider_owner;
GRANT USAGE ON SCHEMA public TO design_provider_app;
ALTER DEFAULT PRIVILEGES FOR ROLE design_provider_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO design_provider_app;
ALTER DEFAULT PRIVILEGES FOR ROLE design_provider_owner IN SCHEMA public GRANT USAGE ON SEQUENCES TO design_provider_app;
ALTER DEFAULT PRIVILEGES FOR ROLE design_provider_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER ROLE design_provider_app IN DATABASE zhongshu_design_provider SET search_path = public;
ALTER ROLE design_provider_owner IN DATABASE zhongshu_design_provider SET search_path = public;
SQL
unset DESIGN_OWNER_PASSWORD DESIGN_APP_PASSWORD
