#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${TRADEPASS_TEST_SEATA_SERVER:-}" ]]; then
  exec bash "$ROOT_DIR/scripts/ci/with-seata.sh" bash "$ROOT_DIR/scripts/verify-backend.sh"
fi
if [[ -z "${TRADEPASS_TEST_ROCKETMQ_SERVER:-}" ]]; then
  exec bash "$ROOT_DIR/scripts/ci/with-rocketmq.sh" bash "$ROOT_DIR/scripts/verify-backend.sh"
fi
: "${TRADEPASS_TEST_MYSQL_URL:?Set a dedicated tradepass_fix_validation_* JDBC URL}"
: "${TRADEPASS_TEST_SERVICES_URL:?Set a separate dedicated tradepass_fix_validation_* JDBC URL for six-process tests}"
: "${TRADEPASS_TEST_MYSQL_USERNAME:?Set the test database username}"
: "${TRADEPASS_TEST_MYSQL_PASSWORD:?Set the test database password}"

# Test classes enforce the database name guard again before opening a connection.
TEST_URL_PATTERN='^jdbc:mysql://[^/]+/(tradepass_fix_validation_[a-zA-Z0-9_]+)(\?.*)?$'
if [[ ! "$TRADEPASS_TEST_MYSQL_URL" =~ $TEST_URL_PATTERN ]]; then
  echo "Workflow tests require a dedicated tradepass_fix_validation_* database." >&2
  exit 1
fi
WORKFLOW_DATABASE="${BASH_REMATCH[1]}"
if [[ ! "$TRADEPASS_TEST_SERVICES_URL" =~ $TEST_URL_PATTERN ]]; then
  echo "Microservice tests require a dedicated tradepass_fix_validation_* database." >&2
  exit 1
fi
SERVICES_DATABASE="${BASH_REMATCH[1]}"
if [[ "$SERVICES_DATABASE" == "$WORKFLOW_DATABASE" ]]; then
  echo "Microservice tests need a separate database." >&2
  exit 1
fi

cd "$ROOT_DIR"
mvn -B clean verify \
  "-Dtradepass.test.seata.server=$TRADEPASS_TEST_SEATA_SERVER" \
  "-Dtradepass.test.rocketmq.server=$TRADEPASS_TEST_ROCKETMQ_SERVER" \
  "-Dtradepass.test.nacos.server=${TRADEPASS_TEST_NACOS_SERVER:-}" \
  "-Dtradepass.test.mysql.url=$TRADEPASS_TEST_MYSQL_URL" \
  "-Dtradepass.test.services.url=$TRADEPASS_TEST_SERVICES_URL" \
  "-Dtradepass.test.mysql.username=$TRADEPASS_TEST_MYSQL_USERNAME" \
  "-Dtradepass.test.mysql.password=$TRADEPASS_TEST_MYSQL_PASSWORD"
