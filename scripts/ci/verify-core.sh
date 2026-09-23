#!/usr/bin/env bash
# Full compatibility suite against three release JARs, Nacos, MQ and an isolated MySQL.
# Deliberately does not start Seata.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export TRADEPASS_TEST_TOPOLOGY=core
exec bash "$ROOT_DIR/scripts/ci/with-nacos.sh" bash "$ROOT_DIR/scripts/ci/verify.sh"
