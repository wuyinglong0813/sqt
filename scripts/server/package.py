#!/usr/bin/env python3
"""Build a small uploadable installer bundle from an explicit secret-free allowlist."""
import hashlib
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[2]
FILES = [
    "scripts/server/bootstrap.sh", "scripts/server/common.sh", "scripts/server/install-docker.sh",
    "scripts/server/install-ci-agent.sh", "scripts/server/services.py", "scripts/server/package.py",
    "scripts/ci/requirements.txt", "deploy/jenkins/plugins.txt", "docs/server-bootstrap.md", "docs/cicd-deployment.md",
    "docs/server-microservices-cutover.md", "docs/server-microservices-verification.md", "docs/microservice-architecture.md",
    "deploy/server/infra.compose.yml", "deploy/server/infra.localhost.compose.yml", "deploy/server/service.compose.yml", "deploy/server/yudao.compose.yml", "deploy/server/.env.example", "deploy/server/README.md",
    "deploy/server/edge.core.compose.yml", "deploy/server/nginx-core-https.conf",
    "deploy/server/yudao.core.compose.yml", "deploy/server/infra.core.compose.yml", "deploy/server/.env.core.example",
    "deploy/server/mysql/11-core-databases.sh", "scripts/server/init-core-nacos.py", "scripts/server/configure-core-integrations.py", "docs/server-core-cutover.md",
    "scripts/server/configure-core-nacos.py", "deploy/server/nacos/bootstrap.example.yml",
    "scripts/server/tradepass.py", "docs/server-operations.md",
    "scripts/server/setup-core-jenkins.sh", "deploy/jenkins/compose.yml", "Jenkinsfile.core",
    "scripts/server/connect-core-jenkins.py", "scripts/server/build-jenkins-installer.py",
    "scripts/jenkins/release.sh", "scripts/jenkins/identity.sh", "scripts/jenkins/business.sh",
    "scripts/jenkins/gateway.sh", "scripts/jenkins/all.sh",
    "scripts/ci/core_release.py", "scripts/cd/core_apply.py", "scripts/cd/core_ssh.py",
    "docs/server-cos.md", "deploy/server/nacos/tencent-cos.example.yml",
    "scripts/server/control.sh", "scripts/server/all.sh", "scripts/server/apps.sh", "scripts/server/infra.sh",
    "scripts/server/mysql.sh", "scripts/server/redis.sh", "scripts/server/nacos.sh",
    "scripts/server/rocketmq-namesrv.sh", "scripts/server/rocketmq-broker.sh",
    "scripts/server/identity.sh", "scripts/server/business.sh", "scripts/server/gateway.sh", "scripts/server/nginx.sh",
    "deploy/server/mysql/10-owned-databases.sh", "deploy/server/seata/application.yml", "deploy/server/rocketmq/broker.conf",
    "deploy/server/xxl-job/schema.sql", "deploy/server/xxl-job/callback-task.sql", "deploy/server/xxl-job/upstream-3.2.0.sql", "deploy/server/xxl-job/LICENSE",
    "deploy/server/edge.compose.yml", "deploy/server/nginx-https.conf", "scripts/server/arthas.sh",
    "deploy/microservices/monitoring/prometheus.yml", "deploy/microservices/monitoring/alertmanager.yml",
    "deploy/microservices/monitoring/grafana/datasources/prometheus.yml",
    "deploy/microservices/monitoring/grafana/dashboards/tradepass.yml",
    "deploy/microservices/monitoring/dashboards/tradepass-runtime.json", "observability/prometheus/alerts.yml",
]

def main():
    output = ROOT / "dist/tradepass-server-bootstrap.tar.gz"
    output.parent.mkdir(exist_ok=True)
    with tarfile.open(output, "w:gz") as archive:
        for name in FILES:
            path = ROOT / name
            if path.is_symlink() or not path.is_file(): raise ValueError("Missing regular package input: " + name)
            archive.add(path, arcname="tradepass-server-bootstrap/" + name, recursive=False)
    checksum = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + ".sha256").write_text(checksum + "  " + output.name + "\n")
    print(output)
    print("SHA256: " + checksum)

if __name__ == "__main__": main()
