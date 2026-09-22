#!/usr/bin/env python3
"""Prepare and start single-server infrastructure; Uses PyYAML to share the reviewed infrastructure Compose definition."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
IMAGES = {
    "mysql": "mysql:8.4", "redis": "redis:7.4-alpine", "nginx": "nginx:stable-alpine",
    "jenkins": "jenkins/jenkins:lts-jdk21",
    "prometheus": "prom/prometheus:v3.13.3", "grafana": "grafana/grafana:13.2.1",
    "alertmanager": "prom/alertmanager:v0.34.0", "node-exporter": "prom/node-exporter:v1.12.1",
}

def write_once(path, content, mode=0o600):
    if path.is_symlink(): raise ValueError("Refusing symlink: " + str(path))
    missing = []
    parent = path.parent
    while not parent.exists():
        missing.append(parent)
        parent = parent.parent
    for parent in reversed(missing):
        parent.mkdir()
        parent.chmod(0o755)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    except FileExistsError:
        return False
    with os.fdopen(fd, "w") as out:
        os.fchmod(out.fileno(), mode)
        out.write(content)
    return True

def read_env(path):
    # Generated values contain no shell expressions. Never source a deployment .env.
    values = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    return values

def json_text(value): return json.dumps(value, indent=2) + "\n"

def compose_document(installation, network):
    common = {"restart": "unless-stopped", "networks": ["services"],
              "logging": {"driver": "json-file", "options": {"max-size": "10m", "max-file": "3"}}}
    def service(name, **kwargs):
        return {**common, "image": IMAGES[name], **kwargs}
    def health(command, start="30s"):
        return {"test": ["CMD-SHELL", command], "interval": "10s", "timeout": "5s", "retries": 12, "start_period": start}
    def config_mount(source, target):
        # Only application-owned configuration is relabeled, never host system paths.
        # Plain Compose file secrets are bind mounts without a SELinux label option.
        return {"type": "bind", "source": source, "target": target, "read_only": True,
                "bind": {"create_host_path": False, "selinux": "z"}}
    def secret_mount(name):
        return config_mount("./secrets/" + name, "/run/secrets/" + name)
    result = {"services": {
        "mysql": service("mysql", mem_limit="1536m", stop_grace_period="90s",
            command=["--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci", "--innodb-buffer-pool-size=512M", "--max-connections=150"],
            environment={"MYSQL_ROOT_PASSWORD_FILE": "/run/secrets/mysql-root", "MYSQL_PASSWORD_FILE": "/run/secrets/mysql-app", "MYSQL_DATABASE": "tradepass_staging", "MYSQL_USER": "tradepass_staging"},
            volumes=["mysql-data:/var/lib/mysql", secret_mount("mysql-root"), secret_mount("mysql-app")],
            healthcheck=health('MYSQL_PWD="$$(cat /run/secrets/mysql-app)" mysql --protocol=TCP -h127.0.0.1 -utradepass_staging tradepass_staging -Nse "SELECT 1" >/dev/null', "90s")),
        "redis": service("redis", mem_limit="384m", command=["redis-server", "/etc/redis/redis.conf"],
            volumes=["redis-data:/data", config_mount("./redis.conf", "/etc/redis/redis.conf"), secret_mount("redis-password")],
            healthcheck=health('REDISCLI_AUTH="$$(cat /run/secrets/redis-password)" redis-cli ping | grep -qx PONG')),
        "nginx": service("nginx", profiles=["edge"], mem_limit="128m", ports=["127.0.0.1:18000:80"],
            volumes=[config_mount("./nginx.conf", "/etc/nginx/conf.d/default.conf")],
            healthcheck=health('wget -q -O /dev/null http://127.0.0.1/_nginx_health')),
        "jenkins": service("jenkins", profiles=["jenkins"], mem_limit="1536m",
            ports=["127.0.0.1:18080:8080"], extra_hosts=["host.docker.internal:host-gateway"],
            environment={"JAVA_OPTS": "-Xms256m -Xmx768m -Djava.awt.headless=true"},
            volumes=["jenkins-home:/var/jenkins_home"],
            healthcheck=health('curl -fsS http://127.0.0.1:8080/login >/dev/null', "180s")),
        "prometheus": service("prometheus", profiles=["monitoring"], mem_limit="768m",
            ports=["127.0.0.1:19090:9090"],
            command=["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=7d", "--storage.tsdb.retention.size=5GB"],
            volumes=[config_mount("./monitoring/prometheus.yml", "/etc/prometheus/prometheus.yml"), config_mount("./monitoring/alerts.yml", "/etc/prometheus/alerts.yml"), "prometheus-data:/prometheus"],
            healthcheck=health('wget -q -O /dev/null http://127.0.0.1:9090/-/ready')),
        "alertmanager": service("alertmanager", profiles=["monitoring"], mem_limit="256m",
            ports=["127.0.0.1:19093:9093"],
            volumes=[config_mount("./monitoring/alertmanager.yml", "/etc/alertmanager/alertmanager.yml"), "alertmanager-data:/alertmanager"],
            healthcheck=health('wget -q -O /dev/null http://127.0.0.1:9093/-/ready')),
        "grafana": service("grafana", profiles=["monitoring"], mem_limit="768m",
            ports=["127.0.0.1:13000:3000"],
            environment={"GF_SECURITY_ADMIN_PASSWORD__FILE": "/run/secrets/grafana-password", "GF_USERS_ALLOW_SIGN_UP": "false"},
            volumes=[secret_mount("grafana-password"), config_mount("./monitoring/grafana", "/etc/grafana/provisioning"), config_mount("./monitoring/dashboards", "/var/lib/grafana/dashboards"), "grafana-data:/var/lib/grafana"],
            healthcheck=health('wget -q -O /dev/null http://127.0.0.1:3000/api/health')),
        "node-exporter": service("node-exporter", profiles=["monitoring"], mem_limit="128m", pid="host",
            read_only=True, cap_drop=["ALL"], security_opt=["no-new-privileges:true"],
            command=["--path.rootfs=/host", "--path.procfs=/host/proc", "--path.sysfs=/host/sys", "--no-collector.netdev", "--no-collector.netstat", "--no-collector.sockstat"],
            volumes=[{"type": "bind", "source": "/", "target": "/host", "read_only": True, "bind": {"propagation": "rslave"}}],
            healthcheck=health('wget -q -O /dev/null http://127.0.0.1:9100/metrics')),
    }, "networks": {"services": {"external": True, "name": network}},
       "volumes": {name: {"labels": {"tradepass.installation": installation}} for name in
                   ("mysql-data", "redis-data", "jenkins-home", "prometheus-data", "alertmanager-data", "grafana-data")}}
    # Keep static and generated server installations on the same database/middleware topology.
    import yaml
    template = yaml.safe_load((REPO / "deploy/server/infra.compose.yml").read_text())
    for name, source in template["services"].items():
        if name == "redis": continue
        item = dict(source)
        item["networks"] = ["services"]
        mounts = []
        for mount in item.get("volumes", []):
            if isinstance(mount, str) and mount.startswith("./"):
                source, target, *_ = mount.split(":")
                mounts.append(config_mount(source, target))
            else: mounts.append(mount)
        if mounts: item["volumes"] = mounts
        if name == "mysql":
            item["environment"].pop("MYSQL_ROOT_PASSWORD", None)
            item["environment"]["MYSQL_ROOT_PASSWORD_FILE"] = "/run/secrets/mysql-root"
            item["volumes"].append(secret_mount("mysql-root"))
            item["healthcheck"] = health('MYSQL_PWD="$$(cat /run/secrets/mysql-root)" mysql --protocol=TCP -h127.0.0.1 -uroot -Nse "SELECT 1" >/dev/null', "90s")
        item.pop("profiles", None)  # Contract defaults require MQ and the job administrator.
        result["services"][name] = item
    for name in template["volumes"]:
        result["volumes"].setdefault(name, {"labels": {"tradepass.installation": installation}})
    return result

def prepare(root, project="tradepass-infra", network="tradepass-staging-services"):
    if root.is_symlink() or (root / "infra").is_symlink() or (root / "staging").is_symlink():
        raise ValueError("Installation directories must not be symlinks")
    infra = root / "infra"
    infra.mkdir(parents=True, exist_ok=True, mode=0o700)
    infra.chmod(0o700)
    state_file = infra / ".env"
    if not state_file.exists() and any(infra.iterdir()):
        raise ValueError("Existing infra configuration lost its .env; restore the original secrets before continuing")
    initial = {"INSTALLATION_ID": secrets.token_hex(16), "COMPOSE_PROJECT": project, "DOCKER_NETWORK": network,
               **{key: secrets.token_hex(32) for key in ("MYSQL_ROOT_PASSWORD", "DB_PASSWORD", "REDIS_PASSWORD", "GRAFANA_ADMIN_PASSWORD", "TRADEPASS_INTERNAL_KEY", "IDENTITY_DB_PASSWORD", "CONTRACT_DB_PASSWORD", "TRADE_DB_PASSWORD", "SETTLEMENT_DB_PASSWORD", "XXL_JOB_DB_PASSWORD", "XXL_JOB_ADMIN_PASSWORD", "XXL_JOB_ACCESS_TOKEN", "SEATA_CONSOLE_PASSWORD")}}
    initial["SEATA_CONSOLE_USERNAME"] = "tradepass"
    initial["SEATA_SECURITY_SECRET_KEY"] = __import__("base64").b64encode(secrets.token_bytes(32)).decode()
    write_once(state_file, "".join(key + "=" + value + "\n" for key, value in initial.items()))
    state_file.chmod(0o600)
    values = read_env(state_file)
    if values.get("COMPOSE_PROJECT") != project or values.get("DOCKER_NETWORK") != network:
        raise ValueError("Existing installation has a different project/network; reuse its original arguments")
    for key in initial:
        if not values.get(key): raise ValueError("Missing installation setting: " + key)
    for key in ("MYSQL_ROOT_PASSWORD", "DB_PASSWORD", "REDIS_PASSWORD", "GRAFANA_ADMIN_PASSWORD", "TRADEPASS_INTERNAL_KEY", "IDENTITY_DB_PASSWORD", "CONTRACT_DB_PASSWORD", "TRADE_DB_PASSWORD", "SETTLEMENT_DB_PASSWORD", "XXL_JOB_DB_PASSWORD", "XXL_JOB_ADMIN_PASSWORD", "XXL_JOB_ACCESS_TOKEN", "SEATA_CONSOLE_PASSWORD"):
        if not re.fullmatch(r"[a-f0-9]{64}", values[key]): raise ValueError("Bootstrap credentials must remain 64-character hex values: " + key)
    (infra / "secrets").mkdir(exist_ok=True, mode=0o700)
    for name, key in (("mysql-root", "MYSQL_ROOT_PASSWORD"), ("mysql-app", "DB_PASSWORD"), ("redis-password", "REDIS_PASSWORD"), ("grafana-password", "GRAFANA_ADMIN_PASSWORD")):
        path = infra / "secrets" / name
        # Readable by the container UID; the host parent directories are root-only.
        write_once(path, values[key] + "\n", 0o644)
        if path.read_text().strip() != values[key]: raise ValueError("Secret mismatch: " + name + "; restore consistent credentials, do not regenerate passwords")
    for directory in ("mysql", "seata", "xxl-job", "rocketmq"):
        for source_file in (REPO / "deploy/server" / directory).glob("*"):
            if source_file.is_file():
                write_once(infra / directory / source_file.name, source_file.read_text(), 0o644)
    write_once(infra / "redis.conf", "bind 0.0.0.0\nprotected-mode yes\nappendonly yes\ndir /data\nmaxmemory 128mb\nmaxmemory-policy allkeys-lru\nrequirepass " + values["REDIS_PASSWORD"] + "\n", 0o644)
    write_once(infra / "compose.json", json_text(compose_document(values["INSTALLATION_ID"], network)), 0o600)
    source = REPO / "deploy/microservices/monitoring"
    for path in sorted(source.rglob("*")):
        if path.is_file():
            contents = path.read_text()
            if path.name == "prometheus.yml":
                contents = contents.replace("alerting:\n", "  - job_name: node\n    static_configs:\n      - targets: [node-exporter:9100]\nalerting:\n")
            write_once(infra / "monitoring" / path.relative_to(source), contents, 0o644)
    # Node exporter reports host CPU, memory and disks. Network collectors are disabled
    # because it deliberately uses a private Docker network instead of the host network.
    write_once(infra / "monitoring/alerts.yml", (REPO / "observability/prometheus/alerts.yml").read_text() + HOST_ALERTS, 0o644)
    write_once(infra / "nginx.conf", NGINX_CONFIG, 0o644)
    stage = root / "staging"
    stage.mkdir(exist_ok=True, mode=0o750)
    write_once(stage / ".env", "\n".join([
        "# Generated once. Edit on the server; never commit credentials.",
        "TRADEPASS_ENVIRONMENT=staging", "TRADEPASS_NETWORK_NAME=" + network, "TRADEPASS_NETWORK_EXTERNAL=true",
        *[line for role in ("identity", "contract", "trade", "settlement") for line in (
            role.upper() + "_DATABASE_URL=jdbc:mysql://mysql:3306/tradepass_staging_" + role + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
            role.upper() + "_DB_USERNAME=tradepass_" + role,
            role.upper() + "_DB_PASSWORD=" + values[role.upper() + "_DB_PASSWORD"])],
        "SEATA_SERVER_ADDR=seata:8091", "TRADEPASS_CONTRACT_PROFILES=observability,messaging,jobs",
        "ROCKETMQ_NAME_SERVER=rocketmq-namesrv:9876", "XXL_JOB_ADMIN_ADDRESSES=http://xxl-job-admin:8080/xxl-job-admin",
        "XXL_JOB_ACCESS_TOKEN=" + values["XXL_JOB_ACCESS_TOKEN"],
        "TRADEPASS_INTERNAL_KEY=" + values["TRADEPASS_INTERNAL_KEY"], "TRADEPASS_IDS_DATACENTER_ID=2",
        "TRADEPASS_GATEWAY_BIND=127.0.0.1", "TRADEPASS_GATEWAY_PORT=1110",
        "TRADEPASS_REDIS_ENABLED=false", "REDIS_HOST=redis", "REDIS_PORT=6379", "REDIS_PASSWORD=" + values["REDIS_PASSWORD"],
        "WECHAT_APP_ID=", "WECHAT_APP_SECRET=", "FADADA_ENABLED=false", "FADADA_APP_ID=", "FADADA_APP_SECRET=", "FADADA_CALLBACK_URL=",
        "TRADEPASS_STORAGE_ENABLED=false", "TRADEPASS_STORAGE_REQUIRED=false", "CLOUDBASE_STORAGE_BUCKET=", "CLOUDBASE_STORAGE_REGION=ap-shanghai", ""]))
    return values

NGINX_CONFIG = '''server {
    listen 80;
    server_name _;
    server_tokens off;
    client_max_body_size 0;
    resolver 127.0.0.11 valid=10s ipv6=off;
    location = /_nginx_health { access_log off; return 200 'ok'; }
    location / {
        set $tradepass_gateway http://gateway:8080;
        proxy_pass $tradepass_gateway;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 180s;
    }
}
'''

HOST_ALERTS = '''
  - name: tradepass-host
    rules:
      - alert: TradePassHostExporterDown
        expr: up{job="node"} == 0
        for: 2m
        labels: {severity: critical}
        annotations: {summary: 'Host metrics are unreachable'}
      - alert: TradePassHostMemoryLow
        expr: node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes < 0.1
        for: 5m
        labels: {severity: warning}
        annotations: {summary: 'Host available memory below 10%'}
      - alert: TradePassHostDiskLow
        expr: node_filesystem_avail_bytes{fstype!~"tmpfs|overlay|squashfs"} / node_filesystem_size_bytes < 0.15 and node_filesystem_readonly == 0
        for: 5m
        labels: {severity: warning}
        annotations: {summary: 'Host available disk space below 15%'}
'''

def docker_environment():
    env = {k: v for k, v in os.environ.items() if not k.startswith(("COMPOSE_", "DOCKER_"))}
    env["DOCKER_HOST"] = "unix:///var/run/docker.sock"
    return env

def docker(*args, capture=False):
    result = subprocess.run(["docker", *args], check=True, text=True, env=docker_environment(),
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None

def check_existing_volumes(project, installation):
    names = docker("volume", "ls", "--format", "{{.Name}}", capture=True).splitlines()
    for name in names:
        if name.startswith(project + "_"):
            info = json.loads(docker("volume", "inspect", name, capture=True))[0]
            if not installation or (info.get("Labels") or {}).get("tradepass.installation") != installation:
                raise ValueError("Existing volume belongs to a different installation: " + name + "; restore its original /opt/tradepass/infra configuration")

def start(root, values, jenkins=False, monitoring=False, nginx=False):
    infra = root / "infra"
    project, network = values["COMPOSE_PROJECT"], values["DOCKER_NETWORK"]
    check_existing_volumes(project, values["INSTALLATION_ID"])
    networks = docker("network", "ls", "--format", "{{.Name}}", capture=True).splitlines()
    if network in networks:
        info = json.loads(docker("network", "inspect", network, capture=True))[0]
        if (info.get("Labels") or {}).get("tradepass.installation") != values["INSTALLATION_ID"]:
            raise ValueError("Existing network is not owned by this installation: " + network)
    else:
        docker("network", "create", "--label", "tradepass.installation=" + values["INSTALLATION_ID"], network)
    selected = ["mysql", "redis", "seata", "rocketmq-namesrv", "rocketmq-broker", "rocketmq-topic-init", "xxl-job-admin"]
    if nginx: selected.append("nginx")
    if jenkins: selected.append("jenkins")
    if monitoring: selected += ["prometheus", "alertmanager", "grafana", "node-exporter"]
    base = ["compose", "--project-name", project, "--env-file", str(infra / ".env"), "-f", str(infra / "compose.json")]
    # Explicit profile names also work with older Compose V2 releases whose `pull`
    # does not activate wildcard profiles consistently.
    if jenkins: base += ["--profile", "jenkins"]
    if monitoring: base += ["--profile", "monitoring"]
    if nginx: base += ["--profile", "edge"]
    lock_path = infra / "images.lock.json"
    locked = json.loads(lock_path.read_text()) if lock_path.exists() else {"services": {}}
    if lock_path.exists(): base += ["-f", str(lock_path)]
    docker(*base, "config", "--quiet")
    # Pull before starting anything. Once locked, reruns use the same immutable images.
    docker(*base, "pull", *selected)
    configured = json.loads(docker(*base, "config", "--format", "json", capture=True))["services"]
    for name in selected:
        image = configured[name]["image"]
        digests = json.loads(docker("image", "inspect", "--format", "{{json .RepoDigests}}", image, capture=True))
        if not digests: raise ValueError("Cannot resolve image digest: " + image)
        locked["services"][name] = {"image": digests[0]}
    temp = infra / "images.lock.tmp"
    temp.write_text(json_text(locked))
    temp.chmod(0o600)
    temp.replace(lock_path)
    if str(lock_path) not in base: base += ["-f", str(lock_path)]
    docker(*base, "up", "--detach", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "360", *selected)
    docker(*base, "ps")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "up", "status"])
    parser.add_argument("--root", type=Path, default=Path("/opt/tradepass"))
    parser.add_argument("--project", default="tradepass-infra")
    parser.add_argument("--network", default="tradepass-staging-services")
    parser.add_argument("--with-jenkins", action="store_true")
    parser.add_argument("--with-monitoring", action="store_true")
    parser.add_argument("--with-nginx", action="store_true")
    args = parser.parse_args()
    if not args.root.is_absolute() or args.root == Path("/"): parser.error("Use an absolute installation directory, never /")
    if not all(re.fullmatch(r"tradepass-[a-z0-9-]+", value) for value in (args.project, args.network)):
        parser.error("Project and network names must begin with tradepass- and contain lowercase letters, digits or hyphens")
    if args.action == "prepare":
        prepare(args.root, args.project, args.network)
        print("Configuration prepared only; no Docker operation or database migration: " + str(args.root))
        return
    if sys.platform != "linux" or os.geteuid() != 0: parser.error("up/status must run as root on the target Linux server")
    if os.getenv("DOCKER_HOST") not in (None, "", "unix:///var/run/docker.sock") or os.getenv("DOCKER_CONTEXT"):
        parser.error("Only the target server's local Docker socket is supported")
    docker("info", capture=True)
    if args.action == "status":
        values = read_env(args.root / "infra/.env")
        docker("compose", "--project-name", values["COMPOSE_PROJECT"], "--env-file", str(args.root / "infra/.env"), "-f", str(args.root / "infra/compose.json"), "ps")
        return
    args.root.mkdir(parents=True, exist_ok=True)
    with (args.root / ".install.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if not (args.root / "infra/.env").exists(): check_existing_volumes(args.project, None)
        values = prepare(args.root, args.project, args.network)
        start(args.root, values, args.with_jenkins, args.with_monitoring, args.with_nginx)
    print("Infrastructure ready. Credentials: " + str(args.root / "infra/.env") + " (root-only).")
    print("Staging .env preserved/generated; the new database is EMPTY. Migrate/restore V36 before deploying business services.")
    if args.with_jenkins: print("Jenkins: SSH tunnel to 127.0.0.1:18080.")
    if args.with_monitoring: print("Grafana: SSH tunnel to 127.0.0.1:13000. Configure alert receivers separately.")

if __name__ == "__main__":
    try: main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print("Installation stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
