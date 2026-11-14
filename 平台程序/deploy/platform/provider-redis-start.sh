#!/bin/sh
set -eu
umask 077
for secret in /run/secrets/design_redis_app_password /run/secrets/design_redis_backup_password; do
    [ "$(wc -c < "$secret")" -ge 32 ] || { printf '%s\n' 'ERROR: design Redis credentials must contain 32+ bytes' >&2; exit 1; }
done
app_hash=$(sha256sum /run/secrets/design_redis_app_password); app_hash=${app_hash%% *}
backup_hash=$(sha256sum /run/secrets/design_redis_backup_password); backup_hash=${backup_hash%% *}
[ "$app_hash" != "$backup_hash" ] || { printf '%s\n' 'ERROR: design Redis credentials must differ' >&2; exit 1; }
printf 'user default off\nuser design_provider_app on #%s ~* &* +@all -@dangerous +info\nuser design_provider_backup on #%s ~* &* +ping +replconf +psync +sync\n' "$app_hash" "$backup_hash" > /tmp/design-provider.acl
exec redis-server --bind 0.0.0.0 --protected-mode yes --port 6379 \
    --aclfile /tmp/design-provider.acl --dir /data --appendonly yes --appendfsync everysec \
    --save '900 1' --stop-writes-on-bgsave-error yes --maxmemory 192mb \
    --maxmemory-policy noeviction --maxclients 100 --logfile ''
