#!/usr/bin/env bash
set -euo pipefail
set +x
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NACOS_TEST_NAME="tradepass-ci-nacos-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
NACOS_TEST_PORT="$(python3 - <<'PY'
import socket
for port in range(18848,28000):
    sockets=[]
    try:
        for candidate in (port,port+1000):
            s=socket.socket(); s.bind(('127.0.0.1',candidate)); sockets.append(s)
        print(port); break
    except OSError: pass
    finally:
        for s in sockets: s.close()
else: raise RuntimeError('No available Nacos ports')
PY
)"
mkdir -p "$ROOT_DIR/dist/ci"
cleanup() {
  if [[ -n "${NACOS_TEST_CONTAINER:-}" ]]; then
    docker logs "$NACOS_TEST_CONTAINER" > "$ROOT_DIR/dist/ci/nacos.log" 2>&1 || true
    docker rm --force --volumes "$NACOS_TEST_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
NACOS_TEST_CONTAINER="$(docker run --detach --name "$NACOS_TEST_NAME" --label tradepass.purpose=ci \
  --publish "127.0.0.1:${NACOS_TEST_PORT}:8848" --publish "127.0.0.1:$((NACOS_TEST_PORT+1000)):9848" \
  --env MODE=standalone --env NACOS_AUTH_ENABLE=false --env JVM_XMS=128m --env JVM_XMX=384m --env JVM_XMN=64m \
  nacos/nacos-server:v2.3.2-slim)"
NACOS_TEST_READY=false
for attempt in $(seq 1 120); do
  if curl --fail --silent --max-time 2 "http://127.0.0.1:${NACOS_TEST_PORT}/nacos/v1/console/health/readiness" >/dev/null; then NACOS_TEST_READY=true; break; fi
  sleep 1
done
[[ "$NACOS_TEST_READY" == true ]] || { echo 'Isolated Nacos did not become ready.' >&2; exit 1; }
export TRADEPASS_TEST_NACOS_SERVER="127.0.0.1:${NACOS_TEST_PORT}"
"$@"
