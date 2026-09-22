#!/usr/bin/env bash
# Run a command against its own ephemeral TC, never the developer's existing coordinator.
set -euo pipefail
set +x
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$ROOT_DIR/dist/ci"
SEATA_TEST_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
SEATA_TEST_NAME="tradepass-ci-seata-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
cleanup() {
  if [[ -n "${SEATA_TEST_CONTAINER:-}" ]]; then
    docker logs "$SEATA_TEST_CONTAINER" > "$ROOT_DIR/dist/ci/seata.log" 2>&1 || true
    docker rm --force --volumes "$SEATA_TEST_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
SEATA_TEST_CONTAINER="$(docker run --detach --name "$SEATA_TEST_NAME" --label tradepass.purpose=ci \
  --publish "127.0.0.1:${SEATA_TEST_PORT}:${SEATA_TEST_PORT}" \
  --env SEATA_IP=127.0.0.1 --env "SEATA_PORT=${SEATA_TEST_PORT}" \
  --env JVM_XMS=128m --env JVM_XMX=384m --env JVM_XMN=64m apache/seata-server:2.1.0)"
SEATA_TEST_READY=false
for attempt in $(seq 1 90); do
  if python3 - "$SEATA_TEST_PORT" <<'PY'
import socket,sys
try:
    with socket.create_connection(('127.0.0.1',int(sys.argv[1])), timeout=1): pass
except OSError: sys.exit(1)
PY
  then SEATA_TEST_READY=true; break; fi
  sleep 1
done
[[ "$SEATA_TEST_READY" == true ]] || { echo 'Isolated Seata did not become ready.' >&2; exit 1; }
export TRADEPASS_TEST_SEATA_SERVER="127.0.0.1:${SEATA_TEST_PORT}"
"$@"
