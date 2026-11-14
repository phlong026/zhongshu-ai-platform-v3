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
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/tencent-storage-capture.XXXXXX")

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

[ -x "$JAVA" ] && [ -x "$JAVAC" ] || {
    echo "capture failed: an existing Java 17 toolchain is required; set JAVA_HOME" >&2
    exit 2
}
old_ifs=$IFS
IFS=:
for dependency in $CLASSPATH; do
    [ -f "$dependency" ] || {
        echo "capture failed: required cached SDK dependency is missing" >&2
        exit 2
    }
done
IFS=$old_ifs

"$JAVAC" -encoding UTF-8 -cp "$CLASSPATH" -d "$BUILD_DIR" \
    "$SCRIPT_DIR/TencentCloudStorageCapture.java"
"$JAVA" -cp "$BUILD_DIR:$CLASSPATH" TencentCloudStorageCapture "$@"
