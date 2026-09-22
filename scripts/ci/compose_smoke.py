#!/usr/bin/env python3
"""Optional real Docker publication/rollback acceptance test, restricted to an isolated database."""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
from release import ROLES, ROOT, compose_document, dockerfile_for

sys.path.insert(0, str(ROOT / "scripts/cd"))
from compose_release import Publisher, read_pointer

def run(*command):
    return subprocess.check_output(command, text=True, cwd=ROOT).strip()

def main():
    database = os.environ.get("TRADEPASS_CD_TEST_DATABASE_URL", "")
    if not re.fullmatch(r"jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\?.*)?", database):
        raise ValueError("Set TRADEPASS_CD_TEST_DATABASE_URL to a migrated isolated test schema reachable from Docker")
    username = os.environ["TRADEPASS_CD_TEST_DATABASE_USERNAME"]
    password = os.environ["TRADEPASS_CD_TEST_DATABASE_PASSWORD"]
    project = "tradepass-cd-test-" + uuid.uuid4().hex[:10]
    registry_name = project + "-registry"
    gateway_port = int(os.environ.get("TRADEPASS_CD_TEST_GATEWAY_PORT", "18310"))
    registry_port = int(os.environ.get("TRADEPASS_CD_TEST_REGISTRY_PORT", "18501"))
    if not 1024 <= gateway_port <= 65535: raise ValueError("Invalid test gateway port")
    if not 1024 <= registry_port <= 65535: raise ValueError("Invalid test registry port")
    with tempfile.TemporaryDirectory(prefix="tradepass-cd-test-") as directory:
        root = Path(directory)
        registry = None
        try:
            # Listen only on the Docker engine host's loopback, also when the engine runs in a Desktop VM.
            registry = run("docker", "run", "-d", "--name", registry_name, "--label", "tradepass.purpose=cd-test",
                           "--network", "host", "--env", "REGISTRY_HTTP_ADDR=127.0.0.1:" + str(registry_port), "registry:2")
            prefix = "127.0.0.1:" + str(registry_port) + "/test/tradepass"
            images = {}
            for role in ROLES:
                image = prefix + "-" + role + ":acceptance"
                subprocess.run(["docker", "build", "-f", dockerfile_for(role), "-t", image, "."], cwd=ROOT, check=True)
                subprocess.run(["docker", "push", image], check=True)
                digests = json.loads(run("docker", "image", "inspect", "--format", "{{json .RepoDigests}}", image))
                images[role] = next(value for value in digests if value.startswith(prefix + "-" + role + "@"))
            revision = run("git", "rev-parse", "HEAD")
            good, bad = revision + "-900001", revision + "-900002"
            for ident in (good, bad):
                release = {"schemaVersion": 1, "id": ident, "revision": revision, "images": images}
                folder = root / "releases" / ident
                folder.mkdir(parents=True)
                (folder / "release.json").write_text(json.dumps(release))
                compose = compose_document(release)
                if ident == bad:
                    compose["services"]["gateway"]["healthcheck"] = {"test": ["CMD-SHELL", "exit 1"], "interval": "1s", "timeout": "1s", "retries": 1, "start_period": "0s"}
                (folder / "compose.json").write_text(json.dumps(compose))
            env = {"TRADEPASS_ENVIRONMENT": "staging", "TRADEPASS_DATABASE_URL": database, "DB_USERNAME": username,
                   "DB_PASSWORD": password, "TRADEPASS_INTERNAL_KEY": secrets.token_hex(32), "TRADEPASS_IDS_DATACENTER_ID": "30",
                   "TRADEPASS_GATEWAY_PORT": str(gateway_port), "TRADEPASS_GATEWAY_BIND": "127.0.0.1"}
            (root / ".env").write_text("".join(key + "=" + value + "\n" for key, value in env.items()))
            os.chmod(root / ".env", 0o600)
            publisher = Publisher(root, project)
            publisher.activate(good)
            assert read_pointer(root, "current") == good
            try:
                publisher.activate(bad)
            except subprocess.CalledProcessError:
                pass
            else:
                raise AssertionError("Unhealthy release unexpectedly succeeded")
            assert read_pointer(root, "current") == good, "Rollback did not retain the original release"
            output = run("docker", "compose", "--project-name", project, "--env-file", str(root / ".env"),
                         "-f", str(root / "releases" / good / "compose.json"), "ps", "--format", "json")
            status = json.loads(output) if output.lstrip().startswith("[") else [json.loads(line) for line in output.splitlines() if line.strip()]
            assert len(status) == 6 and all(item.get("Health") == "healthy" for item in status), status
            try:
                urllib.request.urlopen("http://127.0.0.1:" + str(gateway_port) + "/api/warehouses", timeout=10)
            except urllib.error.HTTPError as error:
                assert error.code == 401
            else:
                raise AssertionError("Gateway did not preserve authentication after rollback")
            print("PASS: six digest-pinned containers deployed; an unhealthy release rolled back to six healthy containers.")
        finally:
            manifests = list((root / "releases").glob("*/compose.json"))
            if manifests and (root / ".env").exists():
                subprocess.run(["docker", "compose", "--project-name", project, "--env-file", str(root / ".env"), "-f", str(manifests[0]), "down"], check=True)
            if registry:
                subprocess.run(["docker", "rm", "--force", "--volumes", registry], check=True)

if __name__ == "__main__":
    main()
