#!/usr/bin/env bash
# Local-only broker for transport tests. Business payloads never enter this instance.
set -euo pipefail
set +x
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MQ_TEST_TAG="$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
MQ_TEST_NETWORK="tradepass-ci-mq-${MQ_TEST_TAG}"
MQ_TEST_NAMESRV="tradepass-ci-namesrv-${MQ_TEST_TAG}"
MQ_TEST_BROKER="tradepass-ci-broker-${MQ_TEST_TAG}"
MQ_TEST_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/tradepass-mq.XXXXXX")"
mkdir -p "$ROOT_DIR/dist/ci"
read -r MQ_TEST_NAMESRV_PORT MQ_TEST_BROKER_PORT < <(python3 - <<'PY'
import socket
for _ in range(100):
    sockets=[]
    try:
        for port in (0,0):
            s=socket.socket(); s.bind(('127.0.0.1',port)); sockets.append(s)
        broker=sockets[1].getsockname()[1]
        for port in (broker-2,broker+1):
            s=socket.socket(); s.bind(('127.0.0.1',port)); sockets.append(s)
        print(sockets[0].getsockname()[1],broker); break
    except OSError: pass
    finally:
        for s in sockets: s.close()
else: raise RuntimeError('No free broker ports')
PY
)
cleanup() {
  for name in "$MQ_TEST_BROKER" "$MQ_TEST_NAMESRV"; do
    docker logs "$name" > "$ROOT_DIR/dist/ci/$name.log" 2>&1 || true
    docker rm --force --volumes "$name" >/dev/null 2>&1 || true
  done
  docker network rm "$MQ_TEST_NETWORK" >/dev/null 2>&1 || true
  rm -f "$MQ_TEST_DIRECTORY/broker.conf"
  rmdir "$MQ_TEST_DIRECTORY" 2>/dev/null || true
}
trap cleanup EXIT
cat > "$MQ_TEST_DIRECTORY/broker.conf" <<EOF
brokerClusterName=TradePassTest
brokerName=isolated-test-broker
brokerId=0
brokerRole=ASYNC_MASTER
brokerIP1=127.0.0.1
listenPort=${MQ_TEST_BROKER_PORT}
haListenPort=$((MQ_TEST_BROKER_PORT+1))
namesrvAddr=${MQ_TEST_NAMESRV}:9876
flushDiskType=SYNC_FLUSH
autoCreateTopicEnable=true
autoCreateSubscriptionGroup=true
mappedFileSizeCommitLog=67108864
storePathRootDir=/tmp/store
EOF
chmod 644 "$MQ_TEST_DIRECTORY/broker.conf"
docker network create --label tradepass.purpose=ci "$MQ_TEST_NETWORK" >/dev/null
docker run --detach --name "$MQ_TEST_NAMESRV" --label tradepass.purpose=ci --network "$MQ_TEST_NETWORK" \
  --publish "127.0.0.1:${MQ_TEST_NAMESRV_PORT}:9876" \
  --env 'JAVA_OPT_EXT=-Xms64m -Xmx128m -Xmn32m' apache/rocketmq:5.3.4 sh mqnamesrv >/dev/null
docker run --detach --name "$MQ_TEST_BROKER" --label tradepass.purpose=ci --network "$MQ_TEST_NETWORK" \
  --publish "127.0.0.1:${MQ_TEST_BROKER_PORT}:${MQ_TEST_BROKER_PORT}" \
  --publish "127.0.0.1:$((MQ_TEST_BROKER_PORT-2)):$((MQ_TEST_BROKER_PORT-2))" \
  --mount "type=bind,source=$MQ_TEST_DIRECTORY/broker.conf,target=/tmp/broker.conf,readonly" \
  --env 'JAVA_OPT_EXT=-Xms128m -Xmx256m -Xmn64m -XX:MaxDirectMemorySize=128m' \
  apache/rocketmq:5.3.4 sh mqbroker -c /tmp/broker.conf >/dev/null
MQ_TEST_READY=false
for attempt in $(seq 1 90); do
  if docker logs "$MQ_TEST_BROKER" 2>&1 | grep -q 'boot success'; then MQ_TEST_READY=true; break; fi
  sleep 1
done
[[ "$MQ_TEST_READY" == true ]] || { echo 'Isolated RocketMQ did not become ready.' >&2; exit 1; }
export TRADEPASS_TEST_ROCKETMQ_SERVER="127.0.0.1:${MQ_TEST_NAMESRV_PORT}"
"$@"
