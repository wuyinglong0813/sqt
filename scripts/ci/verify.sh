#!/usr/bin/env bash
set -euo pipefail
set +x
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
for tool in docker java mvn python3; do command -v "$tool" >/dev/null; done
python3 -c 'import yaml'
DOCKER_ENDPOINT="${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}')}"
[[ "$DOCKER_ENDPOINT" == unix://* ]] || { echo 'CI tests require a local Docker daemon on the agent.' >&2; exit 1; }

mkdir -p dist/ci
MYSQL_NAME="tradepass-ci-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:16])')"
export MYSQL_ROOT_PASSWORD
MYSQL_ROOT_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
cleanup() {
  if [[ -n "${MYSQL_CONTAINER:-}" ]]; then
    docker logs "$MYSQL_CONTAINER" > dist/ci/mysql.log 2>&1 || true
    docker rm --force --volumes "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
MYSQL_CONTAINER="$(docker run --detach --name "$MYSQL_NAME" --label tradepass.purpose=ci \
  --publish 127.0.0.1::3306 --env MYSQL_ROOT_PASSWORD mysql:8.4)"
MYSQL_READY=false
for attempt in $(seq 1 90); do
  if docker exec --env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -uroot -e 'SELECT 1' >/dev/null 2>&1; then MYSQL_READY=true; break; fi
  sleep 2
done
[[ "$MYSQL_READY" == true ]] || { echo 'Isolated CI MySQL did not become ready.' >&2; exit 1; }
MYSQL_PORT="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostPort}}' "$MYSQL_CONTAINER")"
docker exec --env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" mysql -uroot -e '
  CREATE DATABASE tradepass_fix_validation_ci_workflow;
  CREATE DATABASE tradepass_fix_validation_ci_services;
'
export TRADEPASS_TEST_MYSQL_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/tradepass_fix_validation_ci_workflow?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
export TRADEPASS_TEST_SERVICES_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/tradepass_fix_validation_ci_services?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
export TRADEPASS_TEST_MYSQL_USERNAME=root TRADEPASS_TEST_MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD"
bash scripts/verify-backend.sh
python3 -m unittest discover -s scripts/ci/tests -v
docker compose --env-file deploy/microservices/.env.example -f deploy/microservices/compose.yml config --quiet
if [[ "${TRADEPASS_TEST_TOPOLOGY:-split}" == core ]]; then exit 0; fi
docker run --rm --network none --read-only --tmpfs /tmp:rw,nosuid,size=128m \
  -v "$ROOT_DIR/observability/prometheus:/work:ro" -w /work \
  --entrypoint /bin/promtool prom/prometheus:v2.55.1 test rules alerts.test.yml
