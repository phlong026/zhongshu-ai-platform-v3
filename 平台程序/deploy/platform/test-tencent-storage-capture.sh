#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
elif [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    JAVA=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java
    JAVAC=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac
else
    JAVA=$(command -v java || true)
    JAVAC=$(command -v javac || true)
fi
M2_ROOT=${M2_ROOT:-"$HOME/.m2/repository"}
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/tencent-storage-capture-test.XXXXXX")
BUILD_DIR=$(CDPATH= cd -- "$BUILD_DIR" && pwd -P)

cleanup() {
    rm -rf "$BUILD_DIR"
}
trap cleanup EXIT HUP INT TERM

COMMON="$M2_ROOT/com/tencentcloudapi/tencentcloud-sdk-java-common/3.1.1280/tencentcloud-sdk-java-common-3.1.1280.jar"
GSON="$M2_ROOT/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar"
OKHTTP="$M2_ROOT/com/squareup/okhttp3/okhttp/3.12.13/okhttp-3.12.13.jar"
LOGGING="$M2_ROOT/com/squareup/okhttp3/logging-interceptor/3.12.13/logging-interceptor-3.12.13.jar"
OKIO="$M2_ROOT/com/squareup/okio/okio/1.15.0/okio-1.15.0.jar"
COMMONS_LOG="$M2_ROOT/commons-logging/commons-logging/1.2/commons-logging-1.2.jar"
INI4J="$M2_ROOT/org/ini4j/ini4j/0.5.4/ini4j-0.5.4.jar"
CLASSPATH="$COMMON:$GSON:$OKHTTP:$LOGGING:$OKIO:$COMMONS_LOG:$INI4J"

old_ifs=$IFS
IFS=:
for dependency in $CLASSPATH; do
    [ -f "$dependency" ] || {
        echo "test failed: required cached SDK dependency is missing" >&2
        exit 2
    }
done
IFS=$old_ifs

"$JAVAC" -Xlint:all -encoding UTF-8 -cp "$CLASSPATH" -d "$BUILD_DIR" \
    "$SCRIPT_DIR/TencentCloudStorageCapture.java" \
    "$SCRIPT_DIR/TencentCloudStorageCaptureTest.java"

LINUX_TEST_IMAGE=eclipse-temurin:17-jre
docker image inspect "$LINUX_TEST_IMAGE" >/dev/null 2>&1 || {
    echo "test failed: cached Linux Java test image is missing" >&2
    exit 2
}
CONTAINER_CP=/work:/deps/common.jar:/deps/gson.jar:/deps/okhttp.jar:/deps/logging.jar:/deps/okio.jar:/deps/commons-log.jar:/deps/ini4j.jar
docker run --rm --pull never --network none --read-only \
    --user "$(id -u):$(id -g)" --tmpfs /tmp:rw,mode=1777 \
    -v "$BUILD_DIR:/work:rw" \
    -v "$COMMON:/deps/common.jar:ro" -v "$GSON:/deps/gson.jar:ro" \
    -v "$OKHTTP:/deps/okhttp.jar:ro" -v "$LOGGING:/deps/logging.jar:ro" \
    -v "$OKIO:/deps/okio.jar:ro" -v "$COMMONS_LOG:/deps/commons-log.jar:ro" \
    -v "$INI4J:/deps/ini4j.jar:ro" \
    "$LINUX_TEST_IMAGE" java -ea -cp "$CONTAINER_CP" TencentCloudStorageCaptureTest

credential="$BUILD_DIR/unsafe-credential.json"
printf '%s\n' '{"SecretId":"offline-id","SecretKey":"offline-key"}' > "$credential"
chmod 0644 "$credential"
if "$JAVA" -cp "$BUILD_DIR:$CLASSPATH" TencentCloudStorageCapture \
    --credential-file "$credential" --instance-id ins-abcdef12 --region ap-shanghai \
    --output-dir "$BUILD_DIR/unsafe-output" >"$BUILD_DIR/stdout" 2>"$BUILD_DIR/stderr"; then
    echo "test failed: 0644 credential was accepted" >&2
    exit 1
fi
grep -q 'permissions must be exactly 0600' "$BUILD_DIR/stderr"
! grep -q 'offline-' "$BUILD_DIR/stdout" "$BUILD_DIR/stderr"
echo "PASS: TencentCloudStorageCapture CLI permission test"

docker run --rm --pull never --network none --read-only \
    --user "$(id -u):$(id -g)" --tmpfs /tmp:rw,mode=1777 \
    -v "$BUILD_DIR:/work:rw" \
    -v "$COMMON:/deps/common.jar:ro" -v "$GSON:/deps/gson.jar:ro" \
    -v "$OKHTTP:/deps/okhttp.jar:ro" -v "$LOGGING:/deps/logging.jar:ro" \
    -v "$OKIO:/deps/okio.jar:ro" -v "$COMMONS_LOG:/deps/commons-log.jar:ro" \
    -v "$INI4J:/deps/ini4j.jar:ro" \
    "$LINUX_TEST_IMAGE" java -ea -cp "$CONTAINER_CP" TencentCloudStorageCaptureTest \
    --emit-evidence /work/python-integration >/dev/null

evidence="$BUILD_DIR/python-integration/evidence.json"
evidence_sha=$(shasum -a 256 "$evidence" | awk '{print $1}')
PYTHONPATH="$SCRIPT_DIR" EVIDENCE_FILE="$evidence" EVIDENCE_SHA="$evidence_sha" python3 - <<'PY'
import os
from pathlib import Path
import tencent_cbs_storage as cbs

cloud = cbs.load_cloud_evidence(Path(os.environ["EVIDENCE_FILE"]), os.environ["EVIDENCE_SHA"],
                                expected_uid=os.geteuid())
assert cloud["instanceId"] == "ins-abcdef12"
assert cloud["systemDiskId"] == "disk-system001"
assert len(cloud["disks"]) == 101
PY
echo "PASS: Java evidence to Python CBS validator integration"
