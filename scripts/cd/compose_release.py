#!/usr/bin/env python3
"""Run on the deployment host. Use versioned, digest-pinned bundles; never build or migrate data."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess

ROLES = ("identity", "contract", "trade", "settlement", "file", "gateway")
RELEASE_ID = r"[a-f0-9]{40}-[0-9]+"

def read_pointer(root, name):
    path = root / name
    if not path.exists(): return None
    value = path.read_text().strip()
    if not re.fullmatch(RELEASE_ID, value): raise ValueError("Invalid release pointer: " + name)
    return value

def write_pointer(root, name, value):
    temp = root / ("." + name + ".tmp")
    temp.write_text(value + "\n")
    os.replace(temp, root / name)

def validate_bundle(root, release_id):
    if not re.fullmatch(RELEASE_ID, release_id): raise ValueError("Invalid release ID")
    directory = root / "releases" / release_id
    if directory.resolve().parent != (root / "releases").resolve(): raise ValueError("Release escapes the release directory")
    manifest = json.loads((directory / "release.json").read_text())
    compose = json.loads((directory / "compose.json").read_text())
    if manifest.get("schemaVersion") != 1 or manifest.get("id") != release_id or set(manifest.get("images", {})) != set(ROLES):
        raise ValueError("Invalid release manifest")
    if set(compose.get("services", {})) != set(ROLES): raise ValueError("Exactly six services are required")
    for role in ROLES:
        image = manifest["images"][role]
        if not re.fullmatch(r"[a-z0-9][a-z0-9./:_-]*-" + role + r"@sha256:[a-f0-9]{64}", image):
            raise ValueError("Release image is not digest-pinned: " + role)
        service = compose["services"][role]
        if service.get("image") != image or "build" in service or service.get("privileged"):
            raise ValueError("Release compose does not match manifest")
    return directory

def check_env(root):
    env_file = root / ".env"
    if not env_file.is_file(): raise ValueError("Prepare the deployment host .env before publishing")
    values = {}
    for line in env_file.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('\"\'')
    if values.get("TRADEPASS_ENVIRONMENT") != "staging":
        raise ValueError("This initial pipeline supports staging only; production storage and migration acceptance remain incomplete")
    required = ["TRADEPASS_INTERNAL_KEY", "TRADEPASS_IDS_DATACENTER_ID", "SEATA_SERVER_ADDR", "XXL_JOB_ACCESS_TOKEN"]
    required += [role.upper() + suffix for role in ROLES[:4] for suffix in ("_DATABASE_URL", "_DB_USERNAME", "_DB_PASSWORD")]
    for key in required:
        if not values.get(key) or "replace" in values[key]: raise ValueError("Deployment setting is missing: " + key)
    databases = []
    for role in ROLES[:4]:
        url = values[role.upper() + "_DATABASE_URL"]
        match = re.fullmatch(r"jdbc:mysql://[^/]+/([A-Za-z0-9_]+)(?:\?.*)?", url)
        if not match or not match[1].endswith("_" + role):
            raise ValueError("Owned database name must end with _" + role)
        databases.append(url.split("?", 1)[0])
    if len(set(databases)) != 4: raise ValueError("Business services must use four separate databases")
    if len(values["XXL_JOB_ACCESS_TOKEN"]) < 32: raise ValueError("XXL-JOB credential is too short")
    if len(values["TRADEPASS_INTERNAL_KEY"]) < 32: raise ValueError("Internal credential is too short")
    if not values["TRADEPASS_IDS_DATACENTER_ID"].isdigit() or not 0 <= int(values["TRADEPASS_IDS_DATACENTER_ID"]) <= 31:
        raise ValueError("Datacenter ID must be from 0 to 31")
    return env_file

class Publisher:
    def __init__(self, root, project, runner=subprocess.run):
        self.root, self.project, self.runner = root, project, runner
        self.env_file = check_env(root)

    def compose(self, release_id, *args):
        directory = validate_bundle(self.root, release_id)
        # Compose must not inherit CI or operator variables that override the server's .env.
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("TRADEPASS_", "DB_", "WECHAT_", "FADADA_", "CLOUDBASE_", "REDIS_", "SPRING_", "COMPOSE_",
                                              "IDENTITY_", "CONTRACT_", "TRADE_", "SETTLEMENT_", "SEATA_", "XXL_", "ROCKETMQ_", "OSS_", "NACOS_", "SENTINEL_"))}
        command = ["docker", "compose", "--project-name", self.project, "--env-file", str(self.env_file),
                   "-f", str(directory / "compose.json"), *args]
        self.runner(command, check=True, env=environment)

    def activate(self, target):
        validate_bundle(self.root, target)
        current = read_pointer(self.root, "current")
        if current == target:
            self.compose(target, "up", "--detach", "--no-build", "--wait", "--wait-timeout", "240")
            return
        if current: validate_bundle(self.root, current)
        # Validate and pull all images before interrupting the current release.
        self.compose(target, "config", "--quiet")
        self.compose(target, "pull", *ROLES)
        try:
            if current: self.compose(current, "stop", "--timeout", "60", *ROLES)
            self.compose(target, "up", "--detach", "--no-build", "--wait", "--wait-timeout", "240")
        except (subprocess.CalledProcessError, KeyboardInterrupt):
            # Stop every new writer before restoring old processes with the same Snowflake IDs.
            self.compose(target, "stop", "--timeout", "60", *ROLES)
            if current:
                self.compose(current, "up", "--detach", "--no-build", "--wait", "--wait-timeout", "240")
                print("Publication failed; restored previous release " + current, flush=True)
            else:
                print("First publication failed; new containers stopped. Database and files were retained.", flush=True)
            raise
        if current: write_pointer(self.root, "previous", current)
        write_pointer(self.root, "current", target)
        print("Active release: " + target, flush=True)

def main():
    def interrupted(signum, frame):
        raise KeyboardInterrupt("Publication interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["deploy", "rollback"])
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--project", default="tradepass-staging")
    parser.add_argument("--release-id")
    args = parser.parse_args()
    if not args.root.is_absolute() or not args.root.is_dir(): parser.error("--root must be an existing absolute deployment directory")
    if not re.fullmatch(r"tradepass-[a-z0-9-]+", args.project): parser.error("Invalid Compose project name")
    endpoint = os.environ.get("DOCKER_HOST") or subprocess.check_output(
        ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], text=True).strip()
    if not endpoint.startswith("unix://"): parser.error("Deploy against the server's local Docker daemon only")
    with (args.root / ".publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        target = args.release_id or (read_pointer(args.root, "previous") if args.action == "rollback" else None)
        if not target: parser.error("No release selected and no previous release recorded")
        Publisher(args.root, args.project).activate(target)

if __name__ == "__main__":
    main()
