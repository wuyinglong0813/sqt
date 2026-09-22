#!/usr/bin/env python3
"""Build/publish immutable images and create a secret-free release bundle."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
ROLES = ("identity", "contract", "trade", "settlement", "file", "gateway")

def dockerfile_for(role):
    if role == "gateway":
        return "tradepass-gateway/Dockerfile"
    return f"tradepass-module-{role}/tradepass-module-{role}-server/Dockerfile"

def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()

def validate_prefix(prefix):
    if not re.fullmatch(r"[a-z0-9][a-z0-9.:-]*/[a-z0-9][a-z0-9/_-]*", prefix):
        raise ValueError("Use a lowercase registry/project prefix, for example ghcr.io/your-org/tradepass")
    return prefix

def validate_release(data):
    if data.get("schemaVersion") != 1 or set(data.get("images", {})) != set(ROLES):
        raise ValueError("Release must contain exactly the six service images")
    if not re.fullmatch(r"[a-f0-9]{40}", data.get("revision", "")):
        raise ValueError("Release requires the full Git commit ID")
    if not re.fullmatch(r"[a-f0-9]{40}-[0-9]+", data.get("id", "")):
        raise ValueError("Release ID must be COMMIT-BUILD_NUMBER")
    for role, image in data["images"].items():
        if not re.fullmatch(r"[a-z0-9][a-z0-9./:_-]*-" + role + r"@sha256:[a-f0-9]{64}", image):
            raise ValueError("An immutable image digest is required for " + role)
    if data["id"].split("-")[0] != data["revision"]:
        raise ValueError("Release ID does not match the commit")

def compose_document(data):
    import yaml
    validate_release(data)
    source = yaml.safe_load((ROOT / "deploy/microservices/compose.yml").read_text())
    result = {"services": {}, "networks": source["networks"]}
    # A bootstrapped server shares this network with its database and monitoring.
    # Without bootstrap settings, Compose retains the usual project-specific network.
    result["networks"]["services"] = {
        "name": "${TRADEPASS_NETWORK_NAME:-${COMPOSE_PROJECT_NAME}_services}",
        "external": "${TRADEPASS_NETWORK_EXTERNAL:-false}",
    }
    for role in ROLES:
        service = dict(source["services"][role])
        service.pop("build", None)
        service["image"] = data["images"][role]
        result["services"][role] = service
    return result

def bundle(data, destination):
    validate_release(data)
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "release.json").write_text(json.dumps(data, indent=2) + "\n")
    (destination / "compose.json").write_text(json.dumps(compose_document(data), indent=2) + "\n")
    shutil.copy2(ROOT / "scripts/cd/compose_release.py", destination / "compose_release.py")
    run(sys.executable, str(ROOT / "scripts/ci/render_k8s.py"), "--release", str(destination / "release.json"),
        "--output", str(destination / "kubernetes.json"))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["build", "publish", "bundle"])
    parser.add_argument("--prefix", default=os.getenv("IMAGE_PREFIX", ""))
    parser.add_argument("--tag", default=os.getenv("IMAGE_TAG", ""))
    parser.add_argument("--output", type=Path, default=ROOT / "dist/release")
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    if args.action == "bundle":
        if not args.manifest: parser.error("--manifest is required")
        bundle(json.loads(args.manifest.read_text()), args.output)
        return
    prefix = validate_prefix(args.prefix)
    if run("git", "status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Commit all source changes before creating a traceable release; Jenkins must build a clean checkout")
    revision = run("git", "rev-parse", "HEAD")
    if not re.fullmatch(re.escape(revision) + r"-[0-9]+", args.tag):
        raise ValueError("Image tag must be the current full Git commit plus Jenkins build number")
    images = {}
    for role in ROLES:
        repository = prefix + "-" + role
        image = repository + ":" + args.tag
        if args.action == "build":
            subprocess.run(["docker", "build", "-f", dockerfile_for(role),
                            "--label", "org.opencontainers.image.revision=" + revision, "--tag", image, "."], cwd=ROOT, check=True)
        else:
            actual_revision = run("docker", "image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', image)
            if actual_revision != revision: raise ValueError("Image revision does not match the current build")
            subprocess.run(["docker", "push", image], check=True)
            digests = json.loads(run("docker", "image", "inspect", "--format", "{{json .RepoDigests}}", image))
            matches = [digest for digest in digests if digest.startswith(repository + "@sha256:")]
            if len(matches) != 1: raise ValueError("Cannot resolve the pushed digest for " + role)
            images[role] = matches[0]
    if args.action == "publish":
        bundle({"schemaVersion": 1, "id": args.tag, "revision": revision,
                "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "images": images}, args.output)

if __name__ == "__main__":
    main()
