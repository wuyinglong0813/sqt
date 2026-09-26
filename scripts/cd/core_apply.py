#!/usr/bin/env python3
"""Host-side publisher for existing Nacos core containers; no DB or Nacos mutations."""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile

import yaml

ROLES = ("identity", "business", "gateway")
IMAGE_ID = r"sha256:[a-f0-9]{64}"
DEFAULT_COMPOSE = Path("/docker/tradepass/jenkins/core.compose.yml")


def run(*command, timeout=600):
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # Compose diagnostics may include interpolated private configuration.
        raise RuntimeError("命令失败：" + " ".join(command[:2]) + "；请检查 Docker/容器日志")
    return result.stdout.strip()


def atomic(path, data):
    if path.is_symlink():
        raise ValueError("拒绝写入符号链接：" + path.name)
    fd, temp = tempfile.mkstemp(prefix=".publish-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(json.dumps(data, indent=2) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def read_json(path, default=None):
    return json.loads(path.read_text()) if path.exists() else default


def container(role):
    return "tradepass-core-" + role + "-1"


def escape_compose(value):
    """Inspect returns literal values; Compose must not interpolate their dollars."""
    if isinstance(value, str):
        return value.replace("$", "$$")
    if isinstance(value, list):
        return [escape_compose(item) for item in value]
    if isinstance(value, dict):
        return {key: escape_compose(item) for key, item in value.items()}
    return value


def snapshot_service(role, info):
    """Capture the supported host-network core layout, independently of old .env files."""
    config, host = info["Config"], info["HostConfig"]
    if host.get("NetworkMode") != "host":
        raise ValueError(role + " 不是现有 host 网络部署，请明确提供 CORE_COMPOSE")
    # These features need an explicit reviewed Compose file; never silently drop them.
    for key in ("Privileged", "Devices", "DeviceRequests", "VolumesFrom", "Links", "PortBindings",
                "AutoRemove", "PublishAllPorts", "Tmpfs", "StorageOpt", "DeviceCgroupRules",
                "BlkioDeviceReadBps", "BlkioDeviceWriteBps", "BlkioDeviceReadIOps", "BlkioDeviceWriteIOps"):
        if host.get(key):
            raise ValueError(role + " 使用特殊 Docker 参数 " + key + "，请明确提供 CORE_COMPOSE")
    for key in ("PidMode", "UTSMode", "UsernsMode", "CgroupParent", "ContainerIDFile"):
        if host.get(key):
            raise ValueError(role + " 使用特殊 Docker 参数 " + key + "，请明确提供 CORE_COMPOSE")
    if host.get("IpcMode") not in (None, "", "private"):
        raise ValueError(role + " 使用特殊 IPC 配置，请明确提供 CORE_COMPOSE")
    policy = host.get("RestartPolicy", {})
    restart = policy.get("Name") or "no"
    if restart == "on-failure" and policy.get("MaximumRetryCount"):
        restart += ":" + str(policy["MaximumRetryCount"])
    service = {"container_name": container(role), "image": info["Image"],
               "network_mode": "host", "restart": restart, "pull_policy": "never",
               "environment": dict(item.split("=", 1) for item in config.get("Env", [])),
               "volumes": [], "labels": {key: value for key, value in (config.get("Labels") or {}).items()
                                         if not key.startswith("com.docker.compose.")}}
    for key, target in (("User", "user"), ("WorkingDir", "working_dir"), ("Entrypoint", "entrypoint"),
                        ("Cmd", "command"), ("StopSignal", "stop_signal"), ("Domainname", "domainname")):
        if config.get(key) is not None:
            service[target] = config[key]
    if config.get("StopTimeout") is not None:
        service["stop_grace_period"] = str(config["StopTimeout"]) + "s"
    # Do not copy the container-ID-derived hostname. Explicit hostnames are preserved.
    if config.get("Hostname") and config["Hostname"] != info.get("Id", "")[:12]:
        service["hostname"] = config["Hostname"]
    for mount in info.get("Mounts", []):
        if mount["Type"] != "bind" or mount.get("Mode", "") not in ("", "rw", "ro"):
            raise ValueError(role + " 使用特殊挂载，请明确提供 CORE_COMPOSE")
        service["volumes"].append({"type": "bind", "source": mount["Source"], "target": mount["Destination"],
                                   "read_only": not mount["RW"], "bind": {"create_host_path": False,
                                   "propagation": mount.get("Propagation") or "rprivate"}})
    for key, target in (("Memory", "mem_limit"), ("MemoryReservation", "mem_reservation"),
                        ("MemorySwap", "memswap_limit"), ("CpuShares", "cpu_shares"),
                        ("CpuPeriod", "cpu_period"), ("CpuQuota", "cpu_quota"),
                        ("CpusetCpus", "cpuset"), ("PidsLimit", "pids_limit"),
                        ("ShmSize", "shm_size"), ("OomScoreAdj", "oom_score_adj"),
                        ("Dns", "dns"), ("DnsOptions", "dns_opt"), ("DnsSearch", "dns_search"),
                        ("ExtraHosts", "extra_hosts"), ("CapAdd", "cap_add"), ("CapDrop", "cap_drop"),
                        ("SecurityOpt", "security_opt"), ("GroupAdd", "group_add"), ("Sysctls", "sysctls")):
        if host.get(key):
            service[target] = host[key]
    for key, target in (("ReadonlyRootfs", "read_only"), ("Init", "init"),
                        ("OomKillDisable", "oom_kill_disable"), ("MemorySwappiness", "mem_swappiness")):
        if host.get(key) is not None:
            service[target] = host[key]
    if host.get("NanoCpus"):
        service["cpus"] = host["NanoCpus"] / 1_000_000_000
    if host.get("Ulimits"):
        service["ulimits"] = {item["Name"]: {"soft": item["Soft"], "hard": item["Hard"]} for item in host["Ulimits"]}
    if host.get("LogConfig", {}).get("Type"):
        service["logging"] = {"driver": host["LogConfig"]["Type"], "options": host["LogConfig"].get("Config") or {}}
    health = config.get("Healthcheck") or {}
    if not health.get("Test") or health["Test"] == ["NONE"]:
        raise ValueError(role + " 缺少容器健康检查，停止发布")
    service["healthcheck"] = {"test": health["Test"]}
    for key, target in (("Interval", "interval"), ("Timeout", "timeout"),
                        ("StartPeriod", "start_period"), ("StartInterval", "start_interval")):
        if health.get(key):
            service["healthcheck"][target] = str(health[key]) + "ns"
    if health.get("Retries"):
        service["healthcheck"]["retries"] = health["Retries"]
    service["tty"] = config.get("Tty", False)
    service["stdin_open"] = config.get("OpenStdin", False)
    return escape_compose(service)


def validate_config(config):
    if config.get("name") != "tradepass-core" or set(config.get("services", {})) != set(ROLES):
        raise ValueError("只支持现有 tradepass-core 三进程配置")
    for role in ROLES:
        service = config["services"][role]
        if service.get("build") or service.get("env_file"):
            raise ValueError("CORE_COMPOSE 必须是已展开的运行配置，不能带 build/env_file；留空可从容器生成")


def validate_manifest(manifest, roles):
    revision = manifest.get("revision", "")
    if (manifest.get("schema") != 1 or not re.fullmatch(r"[a-f0-9]{40}", revision)
            or not re.fullmatch(re.escape(revision) + r"-[0-9]+", manifest.get("release", ""))
            or set(manifest.get("images", {})) != set(roles)
            or manifest.get("delivery") not in ("local", "archive")):
        raise ValueError("发布清单与所选服务不匹配")
    if manifest["delivery"] == "archive" and not re.fullmatch(r"[a-f0-9]{64}", manifest.get("sha256") or ""):
        raise ValueError("镜像包缺少有效校验和")
    for role, image in manifest["images"].items():
        if not re.fullmatch(IMAGE_ID, image.get("id", "")):
            raise ValueError("镜像必须使用不可变 ID")
        if image.get("tag") != "tradepass-" + role + ":" + manifest["release"]:
            raise ValueError("发布镜像标签不匹配")


class Publisher:
    def __init__(self, compose):
        self.compose = compose
        self.directory = compose.parent / "jenkins"
        self.directory.mkdir(mode=0o700, exist_ok=True)
        self.journal = self.directory / "transaction.json"
        self.history = self.directory / "history.json"

    def inspect(self, role):
        data = json.loads(run("docker", "inspect", container(role)))[0]
        labels = data["Config"]["Labels"]
        if labels.get("com.docker.compose.project") != "tradepass-core" or labels.get("com.docker.compose.service") != role:
            raise ValueError("容器归属不匹配：" + role)
        return data

    def compose_run(self, *args):
        return run("docker", "compose", "-p", "tradepass-core", "-f", str(self.compose), *args)

    def snapshot(self):
        if self.journal.exists():
            raise ValueError("存在中断发布；请先 recover")
        config = {"name": "tradepass-core", "services": {
            role: snapshot_service(role, self.inspect(role)) for role in ROLES}}
        # This is a dedicated private deployment snapshot, never the source Compose file.
        self.preflight(config)
        atomic(self.compose, config)

    def preflight(self, config):
        fd, candidate = tempfile.mkstemp(prefix=".core-check-", suffix=".json", dir=str(self.compose.parent))
        os.close(fd)
        try:
            atomic(Path(candidate), config)
            run("docker", "compose", "-p", "tradepass-core", "-f", candidate, "config", "--quiet")
        finally:
            Path(candidate).unlink(missing_ok=True)

    def stop(self, roles):
        self.compose_run("stop", "--timeout", "90", *reversed(roles))

    def up(self, roles):
        for role in roles:
            print("启动并等待健康：" + role, flush=True)
            self.compose_run("up", "-d", "--no-deps", "--no-build", "--pull", "never",
                             "--force-recreate", "--wait", "--wait-timeout", "360", role)
            info = self.inspect(role)
            if info["State"].get("Health", {}).get("Status") != "healthy":
                raise RuntimeError(role + " 未通过容器健康检查")

    def image(self, image_id):
        if not re.fullmatch(IMAGE_ID, image_id):
            raise ValueError("无效镜像 ID")
        return json.loads(run("docker", "image", "inspect", image_id))[0]

    def recover(self):
        journal = read_json(self.journal)
        if not journal:
            raise ValueError("没有需要恢复的中断发布")
        roles = journal["roles"]
        config = journal["before"]
        validate_config(config)
        for role in roles:
            self.image(config["services"][role]["image"])
        self.stop(roles)
        atomic(self.compose, config)
        self.up(roles)
        atomic(self.history, journal["history"])
        self.journal.unlink()
        print("已恢复发布前镜像；Nacos 配置和数据库未回退", flush=True)

    def activate(self, images, release, record_history=True):
        if self.journal.exists():
            raise ValueError("存在中断发布；请先在 Jenkins 选择 recover")
        roles = [role for role in ROLES if role in images]
        if not roles:
            raise ValueError("未选择服务")
        config = yaml.safe_load(self.compose.read_text())
        validate_config(config)
        before = copy.deepcopy(config)
        for role in roles:
            self.image(images[role])
            info = self.inspect(role)
            # Start from the real running image, not a possibly moved local tag.
            if info["State"]["Status"] != "running":
                raise ValueError(role + " 未运行，请先恢复现有服务后再发布")
            before["services"][role]["image"] = info["Image"]
        after = copy.deepcopy(before)
        for role in roles:
            after["services"][role]["image"] = images[role]
        self.preflight(after)
        history = read_json(self.history, {})
        atomic(self.journal, {"roles": roles, "before": before, "history": history})
        try:
            self.stop(roles)
            # JSON is valid YAML. Preserve $$ Compose escapes from the private file.
            atomic(self.compose, after)
            self.compose_run("config", "--quiet")
            self.up(roles)
            for role in roles:
                if self.inspect(role)["Image"] != images[role]:
                    raise RuntimeError(role + " 实际启动的镜像与发布记录不一致")
            updated = copy.deepcopy(history)
            if record_history:
                for role in roles:
                    updated[role] = {"current": images[role], "previous": before["services"][role]["image"], "release": release}
            atomic(self.history, updated)
            self.journal.unlink()
        except (Exception, KeyboardInterrupt):
            print("发布失败，尝试恢复发布前镜像", flush=True)
            self.recover()
            raise
        print("发布完成：" + release + " / " + ", ".join(roles), flush=True)

    def status(self, roles):
        failed = False
        for role in roles:
            info = self.inspect(role)
            state = info["State"]
            health = state.get("Health", {}).get("Status", "none")
            print(role + " " + state["Status"] + " " + health + " " + info["Image"])
            failed |= state["Status"] != "running" or health != "healthy"
        if self.journal.exists():
            print("存在中断发布，需要 recover")
            failed = True
        return int(failed)


def load_images(bundle, roles):
    manifest = read_json(bundle / "release.json")
    validate_manifest(manifest, roles)
    if manifest["delivery"] == "archive":
        digest = hashlib.sha256()
        with (bundle / "images.tar").open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != manifest["sha256"]:
            raise ValueError("镜像包校验失败，未加载镜像")
        run("docker", "image", "load", "-i", str(bundle / "images.tar"), timeout=900)
    arch = run("docker", "info", "--format", "{{.Architecture}}")
    arch = {"x86_64": "amd64", "aarch64": "arm64"}.get(arch, arch)
    for image in manifest["images"].values():
        info = json.loads(run("docker", "image", "inspect", image["id"]))[0]
        if (info["Architecture"] != arch or info["Architecture"] != image["architecture"]
                or info.get("Os") != "linux"
                or info["Config"].get("Labels", {}).get("org.opencontainers.image.revision") != manifest["revision"]):
            raise ValueError("镜像架构或源码版本不匹配")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["deploy", "rollback", "status", "restart", "recover"])
    parser.add_argument("--service", choices=[*ROLES, "all"], required=True)
    parser.add_argument("--compose", default="")
    parser.add_argument("--bundle", type=Path)
    args = parser.parse_args()
    endpoint = os.environ.get("DOCKER_HOST") or run("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
    if not endpoint.startswith("unix://"):
        parser.error("只允许操作服务器本地 Docker")
    compose = Path(args.compose) if args.compose else DEFAULT_COMPOSE
    if not compose.is_absolute() or compose.is_symlink() or (args.compose and not compose.is_file()):
        parser.error("CORE_COMPOSE 必须是现有运行配置的绝对路径；通常留空自动生成即可")
    compose.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    publisher = Publisher(compose)
    roles = list(ROLES) if args.service == "all" else [args.service]
    with (publisher.directory / "publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == "status":
            return publisher.status(roles)
        if not args.compose and args.action != "recover":
            publisher.snapshot()
        if args.action == "recover":
            publisher.recover()
        elif args.action == "restart":
            publisher.activate({role: publisher.inspect(role)["Image"] for role in roles}, "restart", record_history=False)
        elif args.action == "rollback":
            history = read_json(publisher.history, {})
            images = {role: history.get(role, {}).get("previous") for role in roles}
            if not all(images.values()):
                raise ValueError("所选服务尚无 Jenkins 上一版本记录")
            publisher.activate(images, "rollback")
        else:
            if args.bundle is None:
                parser.error("deploy 需要 --bundle")
            if publisher.journal.exists():
                raise ValueError("存在中断发布，请先 recover")
            manifest = load_images(args.bundle, roles)
            publisher.activate({role: manifest["images"][role]["id"] for role in roles}, manifest["release"])
    return 0


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        raise SystemExit(main())
    except (Exception, KeyboardInterrupt) as error:
        # Avoid dumping configuration or SDK/command output containing credentials.
        print("操作失败：" + (str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__))
        raise SystemExit(1)
