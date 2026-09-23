#!/usr/bin/env bash
# For a fresh MySQL volume. Existing volumes need the separate business-schema step in the runbook.
set -euo pipefail
roles=(identity business)
if [[ "${1:-}" == --business-only ]]; then roles=(business); fi
for role in "${roles[@]}"; do
  key="${role^^}_DB_PASSWORD"
  credential="${!key}"
  [[ "$credential" =~ ^[a-f0-9]{64}$ ]] || { echo "Invalid generated credential: $key" >&2; exit 1; }
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE tradepass_staging_${role} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'tradepass_${role}'@'%' IDENTIFIED BY '${credential}';
GRANT ALL PRIVILEGES ON tradepass_staging_${role}.* TO 'tradepass_${role}'@'%';
SQL
done
