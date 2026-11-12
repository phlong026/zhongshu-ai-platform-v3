#!/bin/sh
set -eu
export REDISCLI_AUTH="$(cat /run/secrets/redis_app_password)"
if ! result=$(redis-cli --no-auth-warning --user platform_app -h 127.0.0.1 ping) || [ "$result" != PONG ]; then
    printf '%s\n' 'ERROR: B Redis authenticated PING failed' >&2
    exit 1
fi
# Boot 3.5 RedisHealthIndicator calls INFO; PING alone hides insufficient ACL grants.
if ! info=$(redis-cli --no-auth-warning --user platform_app -h 127.0.0.1 info server) || ! printf '%s\n' "$info" | grep -q '^redis_version:'; then
    printf '%s\n' 'ERROR: B Redis authenticated INFO failed' >&2
    exit 1
fi
