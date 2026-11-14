#!/bin/sh
set -eu
export REDISCLI_AUTH="$(cat /run/secrets/design_redis_app_password)"
[ "$(redis-cli --no-auth-warning --user design_provider_app ping)" = PONG ]
