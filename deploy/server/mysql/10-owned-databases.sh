#!/usr/bin/env bash
# MySQL official image sources this only when its data directory is empty.
# Use independent `openssl rand -hex 32` credentials. Never echo credentials.
set -euo pipefail
for role in identity contract trade settlement; do
  key="${role^^}_DB_PASSWORD"
  credential="${!key}"
  [[ "$credential" =~ ^[a-f0-9]{64}$ ]] || { echo "Invalid generated credential: $key" >&2; exit 1; }
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE tradepass_staging_${role} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'tradepass_${role}'@'%' IDENTIFIED BY '${credential}';
GRANT ALL PRIVILEGES ON tradepass_staging_${role}.* TO 'tradepass_${role}'@'%';
SQL
done
for key in XXL_JOB_DB_PASSWORD XXL_JOB_ADMIN_PASSWORD; do
  [[ "${!key}" =~ ^[a-f0-9]{64}$ ]] || { echo "Invalid generated credential: $key" >&2; exit 1; }
done
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE xxl_job CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'tradepass_jobs'@'%' IDENTIFIED BY '${XXL_JOB_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON xxl_job.* TO 'tradepass_jobs'@'%';
SQL
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot xxl_job < /opt/tradepass/xxl-job/schema.sql
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot xxl_job <<SQL
INSERT INTO xxl_job_user(username,password,role,permission) VALUES ('tradepass',SHA2('${XXL_JOB_ADMIN_PASSWORD}',256),1,NULL);
SQL
