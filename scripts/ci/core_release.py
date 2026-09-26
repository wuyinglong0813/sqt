#!/usr/bin/env python3
"""Export selected core images for SSH delivery, with immutable IDs and a checksum."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
DOCKERFILES = {
    "identity": "tradepass-module-identity/tradepass-module-identity-server/Dockerfile",
    "business": "tradepass-business/Dockerfile",
    "gateway": "tradepass-gateway/Dockerfile",
}


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", choices=[*DOCKERFILES, "all"], required=True)
    parser.add_argument("--release", required=True)
    parser.add_argument("--delivery", choices=["local", "archive"], default="local")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/core-release")
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if not re.fullmatch(re.escape(revision) + r"-[0-9]+", args.release):
        parser.error("Release must be the checked-out full commit plus build number")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        parser.error("Build images only from a committed, clean checkout")
    roles = list(DOCKERFILES) if args.service == "all" else [args.service]
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    images, tags = {}, []
    for role in roles:
        tag = "tradepass-" + role + ":" + args.release
        subprocess.run(["docker", "build", "-f", DOCKERFILES[role], "-t", tag,
                        "--label", "org.opencontainers.image.revision=" + revision, "."], cwd=ROOT, check=True)
        info = json.loads(subprocess.check_output(["docker", "image", "inspect", tag], text=True))[0]
        images[role] = {"id": info["Id"], "tag": tag, "architecture": info["Architecture"]}
        tags.append(tag)
    archive = destination / "images.tar"
    if args.delivery == "archive":
        subprocess.run(["docker", "image", "save", "--output", str(archive), *tags], check=True)
    manifest = {"schema": 1, "release": args.release, "revision": revision,
                "delivery": args.delivery, "sha256": checksum(archive) if args.delivery == "archive" else None,
                "images": images}
    (destination / "release.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("已导出：" + ", ".join(roles) + "，发布版本 " + args.release)


if __name__ == "__main__":
    main()
