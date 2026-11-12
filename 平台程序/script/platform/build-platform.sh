#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

# No clean/install and no -DskipTests: preserve parallel lanes and verify the selected reactor.
python3 -I "${SCRIPT_DIR}/test_release_boundary.py"
python3 -I "${SCRIPT_DIR}/test_artifact_verifier.py"
"${BACKEND_DIR}/mvnw" -f "${BACKEND_DIR}/pom.xml" -B -pl yudao-platform-server -am verify
python3 -I "${SCRIPT_DIR}/verify-platform-artifact.py" \
  "${BACKEND_DIR}/yudao-platform-server/target/yudao-platform-server.jar"
