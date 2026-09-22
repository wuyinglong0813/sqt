#!/usr/bin/env bash
# Validate the packaged images and fresh server initialization on a unique private network.
set -euo pipefail
set +x
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
COMPOSE_TEST_ID="tradepass-ci-server-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
COMPOSE_TEST_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/tradepass-compose.XXXXXX")"
export COMPOSE_TEST_ID COMPOSE_TEST_DIRECTORY
export TRADEPASS_IMAGE_TAG="${TRADEPASS_IMAGE_TAG:-split-validation}"
export TRADEPASS_IMAGE_PREFIX="${TRADEPASS_IMAGE_PREFIX:-tradepass}"
python3 - <<'PY'
import importlib.util, json, os
from pathlib import Path
spec=importlib.util.spec_from_file_location('server', 'scripts/server/services.py')
server=importlib.util.module_from_spec(spec); spec.loader.exec_module(server)
root=Path(os.environ['COMPOSE_TEST_DIRECTORY'])
server.prepare(root, os.environ['COMPOSE_TEST_ID']+'-infra', os.environ['COMPOSE_TEST_ID'])
env=root/'staging/.env'
env.write_text(env.read_text().replace('TRADEPASS_GATEWAY_PORT=1110','TRADEPASS_GATEWAY_PORT=0')+'\nTRADEPASS_IMAGE_TAG='+os.environ['TRADEPASS_IMAGE_TAG']+'\n')
roles=('identity','contract','trade','settlement','file','gateway')
override={'services':{role:{'image':os.environ['TRADEPASS_IMAGE_PREFIX']+'-'+role+':'+os.environ['TRADEPASS_IMAGE_TAG']} for role in roles}}
(root/'images.json').write_text(json.dumps(override))
PY
infra=(docker compose --project-name "$COMPOSE_TEST_ID-infra" --env-file "$COMPOSE_TEST_DIRECTORY/infra/.env" -f "$COMPOSE_TEST_DIRECTORY/infra/compose.json")
business=(docker compose --project-name "$COMPOSE_TEST_ID-business" --env-file "$COMPOSE_TEST_DIRECTORY/staging/.env" -f deploy/server/service.compose.yml -f "$COMPOSE_TEST_DIRECTORY/images.json")
mkdir -p dist/ci/compose
cleanup() {
  "${business[@]}" logs --no-color > dist/ci/compose/business.log 2>&1 || true
  "${infra[@]}" logs --no-color > dist/ci/compose/infra.log 2>&1 || true
  "${business[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  "${infra[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker network rm "$COMPOSE_TEST_ID" >/dev/null 2>&1 || true
  # Remove only this invocation's generated credentials; reports contain no env dump.
  python3 - <<'PY'
import os,shutil
from pathlib import Path
p=Path(os.environ['COMPOSE_TEST_DIRECTORY'])
if p.name.startswith('tradepass-compose.') and p.is_dir(): shutil.rmtree(p)
PY
}
trap cleanup EXIT
docker network create --label tradepass.purpose=ci "$COMPOSE_TEST_ID" >/dev/null
"${infra[@]}" up --detach --wait --wait-timeout 360 mysql redis seata rocketmq-broker xxl-job-admin
"${infra[@]}" run --rm --no-deps rocketmq-topic-init
"${business[@]}" up --detach --no-build --wait --wait-timeout 360
COMPOSE_TEST_GATEWAY="$("${business[@]}" port gateway 8080)"
curl --fail --silent --show-error "http://${COMPOSE_TEST_GATEWAY}/tcb_probe" >/dev/null
JOB_READY=false
for attempt in $(seq 1 90); do
  completed="$("${infra[@]}" exec -T mysql sh -ec 'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot -Nse "SELECT COUNT(*) FROM xxl_job.xxl_job_log WHERE handle_code=200"')"
  if [[ "$completed" =~ ^[1-9][0-9]*$ ]]; then JOB_READY=true; break; fi
  sleep 1
done
[[ "$JOB_READY" == true ]] || { echo 'XXL administrator did not execute the real recovery job.' >&2; exit 1; }
"${infra[@]}" exec -T mysql sh -ec 'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot -Nse "SELECT table_schema,COUNT(*) FROM information_schema.tables WHERE table_schema IN (\"tradepass_staging_identity\",\"tradepass_staging_contract\",\"tradepass_staging_trade\",\"tradepass_staging_settlement\") GROUP BY table_schema"' > dist/ci/compose/owned-table-counts.txt
echo 'Fresh server initialization, six packaged services, gateway and XXL recovery job passed.'
