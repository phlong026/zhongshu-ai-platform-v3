#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IMAGE=${NGINX_TEST_IMAGE:-nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236}
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/platform-nginx-check.XXXXXX")
CONTAINER="platform-nginx-check-$$"

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

assert_status() {
    expected=$1
    url=$2
    host=$3
    actual=$(curl --noproxy '*' -kfsS -o /dev/null -w '%{http_code}' --resolve "$host:$HTTPS_PORT:127.0.0.1" "$url" 2>/dev/null || true)
    [ "$actual" = "$expected" ] || fail "$host $url expected $expected, got ${actual:-none}"
}

assert_body() {
    expected=$1
    url=$2
    host=$3
    actual=$(curl --noproxy '*' -kfsS --resolve "$host:$HTTPS_PORT:127.0.0.1" "$url")
    [ "$actual" = "$expected" ] || fail "$host $url unexpected body: $actual"
}

assert_http_status() {
    expected=$1
    path=$2
    host=$3
    actual=$(curl --noproxy '*' -fsS -o /dev/null -w '%{http_code}' -H "Host: $host" \
        "http://127.0.0.1:$HTTP_PORT$path" 2>/dev/null || true)
    [ "$actual" = "$expected" ] || fail "$host HTTP $path expected $expected, got ${actual:-none}"
}

command -v docker >/dev/null 2>&1 || fail "docker is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v openssl >/dev/null 2>&1 || fail "openssl is required"
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "cached image missing: $IMAGE (the check never pulls)"

for candidate in "$SCRIPT_DIR"/*.conf.candidate; do
    if grep -Eq 'default_server|server_name[[:space:]]+(api|api2)\.zszhj\.com|proxy_pass[[:space:]]+http://127\.0\.0\.1:(48080|48443)' "$candidate"; then
        fail "forbidden A/default/upstream reference in $candidate"
    fi
done

mkdir -p \
    "$TMP_DIR/certs/platform-api.zszhj.com" \
    "$TMP_DIR/certs/platform-admin.zszhj.com" \
    "$TMP_DIR/acme/.well-known/acme-challenge" \
    "$TMP_DIR/web/current/assets"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj '/CN=platform-nginx-isolated.test' \
    -keyout "$TMP_DIR/test.key" -out "$TMP_DIR/test.crt" >/dev/null 2>&1
for domain in platform-api.zszhj.com platform-admin.zszhj.com; do
    cp "$TMP_DIR/test.crt" "$TMP_DIR/certs/$domain/fullchain.pem"
    cp "$TMP_DIR/test.key" "$TMP_DIR/certs/$domain/privkey.pem"
done
printf '%s' 'ACME_OK' > "$TMP_DIR/acme/.well-known/acme-challenge/probe"
printf '%s' 'INDEX_OK' > "$TMP_DIR/web/current/index.html"
printf '%s' 'ASSET_OK' > "$TMP_DIR/web/current/assets/app.js"

cat > "$TMP_DIR/fixtures.conf" <<'EOF'
server {
    listen 127.0.0.1:48081;
    server_name b-upstream-fixture;
    access_log off;
    location = /infra/ws {
        access_log off;
        add_header Upgrade "$http_upgrade" always;
        add_header Connection "$http_connection" always;
        add_header X-Fixture-Upgrade "$http_upgrade" always;
        add_header X-Fixture-Connection "$http_connection" always;
        return 101;
    }
    location / {
        default_type text/plain;
        add_header X-Upstream B always;
        add_header X-Fixture-XFF "$http_x_forwarded_for" always;
        return 200 "B:$request_uri";
    }
    location = /admin-api/upstream-failure {
        return 444;
    }
}
server {
    listen 80 default_server;
    listen 443 ssl default_server;
    server_name api.zszhj.com api2.zszhj.com;
    ssl_certificate /etc/letsencrypt/live/platform-api.zszhj.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/platform-api.zszhj.com/privkey.pem;
    return 418 "A_FIXTURE";
}
EOF

docker run --rm --pull never \
    -v "$SCRIPT_DIR/platform-api.zszhj.com.conf.candidate:/etc/nginx/conf.d/platform-api.conf:ro" \
    -v "$SCRIPT_DIR/platform-admin.zszhj.com.conf.candidate:/etc/nginx/conf.d/platform-admin.conf:ro" \
    -v "$TMP_DIR/fixtures.conf:/etc/nginx/conf.d/fixtures.conf:ro" \
    -v "$TMP_DIR/certs:/etc/letsencrypt/live:ro" \
    -v "$TMP_DIR/acme:/var/lib/zhongshu-platform-b/acme:ro" \
    -v "$TMP_DIR/web:/srv/zhongshu-platform-b/web:ro" \
    "$IMAGE" nginx -t

docker run -d --name "$CONTAINER" --pull never \
    -p 127.0.0.1::80 -p 127.0.0.1::443 \
    -v "$SCRIPT_DIR/platform-api.zszhj.com.conf.candidate:/etc/nginx/conf.d/platform-api.conf:ro" \
    -v "$SCRIPT_DIR/platform-admin.zszhj.com.conf.candidate:/etc/nginx/conf.d/platform-admin.conf:ro" \
    -v "$TMP_DIR/fixtures.conf:/etc/nginx/conf.d/fixtures.conf:ro" \
    -v "$TMP_DIR/certs:/etc/letsencrypt/live:ro" \
    -v "$TMP_DIR/acme:/var/lib/zhongshu-platform-b/acme:ro" \
    -v "$TMP_DIR/web:/srv/zhongshu-platform-b/web:ro" \
    "$IMAGE" >/dev/null

HTTP_PORT=$(docker port "$CONTAINER" 80/tcp | sed -n '1s/.*://p')
HTTPS_PORT=$(docker port "$CONTAINER" 443/tcp | sed -n '1s/.*://p')
[ -n "$HTTP_PORT" ] && [ -n "$HTTPS_PORT" ] || fail "failed to discover isolated ports"

ready=0
i=0
while [ "$i" -lt 30 ]; do
    if curl --noproxy '*' -kfsS --resolve "platform-admin.zszhj.com:$HTTPS_PORT:127.0.0.1" \
        "https://platform-admin.zszhj.com:$HTTPS_PORT/" >/dev/null 2>&1; then
        ready=1
        break
    fi
    i=$((i + 1))
    sleep 0.1
done
[ "$ready" = 1 ] || {
    docker logs "$CONTAINER" >&2 || true
    fail "isolated Nginx did not become ready"
}

acme=$(curl --noproxy '*' -fsS -H 'Host: platform-api.zszhj.com' \
    "http://127.0.0.1:$HTTP_PORT/.well-known/acme-challenge/probe")
[ "$acme" = 'ACME_OK' ] || fail "ACME webroot mismatch"
redirect=$(curl --noproxy '*' -fsS -o /dev/null -D - -H 'Host: platform-api.zszhj.com' \
    "http://127.0.0.1:$HTTP_PORT/admin-api/probe?token=REDIRECT_TOKEN_SENTINEL" | tr -d '\r' | sed -n 's/^Location: //p')
[ "$redirect" = 'https://platform-api.zszhj.com/admin-api/probe?token=REDIRECT_TOKEN_SENTINEL' ] || fail "HTTP redirect mismatch: $redirect"
assert_http_status 404 '/.env' platform-api.zszhj.com
assert_http_status 404 '/.git/config' platform-admin.zszhj.com

assert_body 'B:/admin-api/probe?x=1' "https://platform-api.zszhj.com:$HTTPS_PORT/admin-api/probe?x=1" platform-api.zszhj.com
assert_body 'B:/app-api/probe' "https://platform-api.zszhj.com:$HTTPS_PORT/app-api/probe" platform-api.zszhj.com
assert_status 404 "https://platform-api.zszhj.com:$HTTPS_PORT/actuator/health" platform-api.zszhj.com
assert_status 404 "https://platform-api.zszhj.com:$HTTPS_PORT/admin-api/actuator/health" platform-api.zszhj.com
assert_status 404 "https://platform-api.zszhj.com:$HTTPS_PORT/" platform-api.zszhj.com

headers=$(curl --noproxy '*' -kfsS -o /dev/null -D - --resolve "platform-api.zszhj.com:$HTTPS_PORT:127.0.0.1" \
    "https://platform-api.zszhj.com:$HTTPS_PORT/admin-api/probe" | tr -d '\r')
printf '%s\n' "$headers" | grep -qi '^Cache-Control: no-store, private$' || fail "private API cache policy missing"
printf '%s\n' "$headers" | grep -qi '^X-Upstream: B$' || fail "B upstream evidence missing"

token_body=$(curl --noproxy '*' -kfsS --resolve "platform-api.zszhj.com:$HTTPS_PORT:127.0.0.1" \
    "https://platform-api.zszhj.com:$HTTPS_PORT/admin-api/franchise/assets/42/content?token=DOWNLOAD_TOKEN_SENTINEL")
[ "$token_body" = 'B:/admin-api/franchise/assets/42/content?token=DOWNLOAD_TOKEN_SENTINEL' ] || fail "download path changed"
assert_status 502 "https://platform-api.zszhj.com:$HTTPS_PORT/admin-api/upstream-failure?token=FAILURE_TOKEN_SENTINEL" platform-api.zszhj.com
xff_headers=$(curl --noproxy '*' -kfsS -o /dev/null -D - \
    --resolve "platform-api.zszhj.com:$HTTPS_PORT:127.0.0.1" \
    -H 'X-Forwarded-For: 203.0.113.66' \
    "https://platform-api.zszhj.com:$HTTPS_PORT/admin-api/xff" | tr -d '\r')
printf '%s\n' "$xff_headers" | grep -qi '^X-Fixture-XFF: ' || fail "XFF fixture evidence missing"
if printf '%s\n' "$xff_headers" | grep -q '203\.0\.113\.66'; then
    fail "client supplied XFF reached B upstream"
fi

for ws_host in platform-api.zszhj.com platform-admin.zszhj.com; do
    ws_headers=$(curl --noproxy '*' -kfsS --http1.1 --max-time 1 -o /dev/null -D - \
        --resolve "$ws_host:$HTTPS_PORT:127.0.0.1" \
        -H 'Upgrade: websocket' -H 'Connection: Upgrade' \
        -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
        "https://$ws_host:$HTTPS_PORT/infra/ws?token=DO_NOT_LOG" 2>/dev/null | tr -d '\r')
    printf '%s\n' "$ws_headers" | grep -q '^HTTP/1.1 101 ' || fail "$ws_host WebSocket did not upgrade"
    printf '%s\n' "$ws_headers" | grep -qi '^X-Fixture-Upgrade: websocket$' || fail "$ws_host did not forward Upgrade"
    printf '%s\n' "$ws_headers" | grep -qi '^X-Fixture-Connection: upgrade$' || fail "$ws_host did not forward Connection"
    assert_status 404 "https://$ws_host:$HTTPS_PORT/infra/ws/extra" "$ws_host"
done
if docker logs "$CONTAINER" 2>&1 | grep -q 'DO_NOT_LOG'; then
    fail "WebSocket query token appeared in isolated Nginx logs"
fi

assert_body 'B:/admin-api/probe' "https://platform-admin.zszhj.com:$HTTPS_PORT/admin-api/probe" platform-admin.zszhj.com
assert_status 404 "https://platform-admin.zszhj.com:$HTTPS_PORT/admin-api/actuator/health" platform-admin.zszhj.com
assert_status 404 "https://platform-admin.zszhj.com:$HTTPS_PORT/app-api/probe" platform-admin.zszhj.com
assert_body 'INDEX_OK' "https://platform-admin.zszhj.com:$HTTPS_PORT/dashboard/projects" platform-admin.zszhj.com
assert_body 'ASSET_OK' "https://platform-admin.zszhj.com:$HTTPS_PORT/assets/app.js" platform-admin.zszhj.com
assert_status 404 "https://platform-admin.zszhj.com:$HTTPS_PORT/assets/missing.js" platform-admin.zszhj.com
assert_status 404 "https://platform-admin.zszhj.com:$HTTPS_PORT/missing.js" platform-admin.zszhj.com
assert_status 404 "https://platform-admin.zszhj.com:$HTTPS_PORT/.env" platform-admin.zszhj.com
assert_status 404 "https://platform-admin.zszhj.com:$HTTPS_PORT/assets/.git/config" platform-admin.zszhj.com

a_status=$(curl --noproxy '*' -kfsS -o /dev/null -w '%{http_code}' --resolve "api2.zszhj.com:$HTTPS_PORT:127.0.0.1" \
    "https://api2.zszhj.com:$HTTPS_PORT/admin-api/probe" 2>/dev/null || true)
[ "$a_status" = 418 ] || fail "A fixture was unexpectedly claimed by B candidates: $a_status"

sanitized_logs=$(docker exec "$CONTAINER" sh -c \
    'cat /var/log/nginx/zhongshu-platform-b-*.access.log /var/log/nginx/zhongshu-platform-b-*.error.log 2>/dev/null || true')
if docker exec "$CONTAINER" sh -c 'ls /var/log/nginx/zhongshu-platform-b-*.error.log >/dev/null 2>&1'; then
    fail "B request-level native error log must not exist"
fi
for sentinel in DOWNLOAD_TOKEN_SENTINEL FAILURE_TOKEN_SENTINEL REDIRECT_TOKEN_SENTINEL DO_NOT_LOG; do
    if printf '%s\n' "$sanitized_logs" | grep -q "$sentinel"; then
        fail "sensitive query sentinel appeared in B Nginx logs: $sentinel"
    fi
done
printf '%s\n' "$sanitized_logs" | grep -q 'GET /admin-api/franchise/assets/42/content HTTP/1.1' || fail "sanitized URI log missing"
printf '%s\n' "$sanitized_logs" | grep -q 'GET /admin-api/upstream-failure HTTP/1.1.*upstream_status=502' || fail "sanitized upstream failure status missing"

cleanup
trap - EXIT HUP INT TERM
echo "PASS: nginx syntax, B hosts, API/WebSocket path preservation, 101 Upgrade, SPA/static 404, ACME/redirect, private-cache and A-host isolation"
