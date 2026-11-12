#!/bin/sh
set -eu
umask 077
# Hash raw secret bytes; provisioning rejects trailing newlines and re-used credentials.
for secret in /run/secrets/redis_app_password /run/secrets/redis_backup_password; do
    if [ "$(wc -c < "$secret")" -lt 32 ]; then
        printf '%s\n' 'ERROR: Redis requires external credentials (32+ bytes)' >&2
        exit 1
    fi
done
app_hash=$(sha256sum /run/secrets/redis_app_password)
app_hash=${app_hash%% *}
backup_hash=$(sha256sum /run/secrets/redis_backup_password)
backup_hash=${backup_hash%% *}
if [ "$app_hash" = "$backup_hash" ]; then
    printf '%s\n' 'ERROR: Redis app and backup credentials must differ' >&2
    exit 1
fi
# ACLs apply to a dedicated B instance; session/cache keys may use existing prefixes.
printf 'user default off\nuser platform_app on #%s ~* &* +@all -@dangerous +info\nuser platform_backup on #%s ~* &* +ping +replconf +psync +sync\n' "$app_hash" "$backup_hash" > /tmp/platform.acl
exec redis-server --bind 0.0.0.0 --protected-mode yes --port 6379 \
    --aclfile /tmp/platform.acl --dir /data --appendonly yes --appendfsync everysec \
    --save '900 1' --stop-writes-on-bgsave-error yes --maxmemory 192mb \
    --maxmemory-policy noeviction --maxclients 200 --logfile ''
