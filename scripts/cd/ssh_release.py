#!/usr/bin/env python3
"""Copy a reviewed release bundle over verified SSH, then invoke the host-side publisher."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import uuid
import tarfile

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["deploy", "rollback"])
    parser.add_argument("--host", required=True)
    parser.add_argument("--user", default="tradepass-deploy")
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--known-hosts", type=Path, required=True)
    parser.add_argument("--identity-file", type=Path)
    parser.add_argument("--root", default="/opt/tradepass/staging")
    parser.add_argument("--bundle", type=Path, default=Path("dist/release"))
    parser.add_argument("--release-id")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]*", args.host): parser.error("Invalid SSH host")
    if not re.fullmatch(r"[a-z_][a-z0-9_-]*", args.user): parser.error("Invalid SSH user")
    if not re.fullmatch(r"/[a-zA-Z0-9/_-]+", args.root) or args.root in ("/", "/opt"): parser.error("Use a dedicated deployment directory")
    if not args.known_hosts.is_file(): parser.error("Provide verified SSH known_hosts as a Jenkins file credential")
    if not 1 <= args.port <= 65535: parser.error("Invalid SSH port")
    identity_options = []
    if args.identity_file is not None:
        if not args.identity_file.is_file(): parser.error("SSH private key file does not exist")
        identity_options = ["-i", str(args.identity_file.resolve()), "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none"]
    ssh = ["ssh", "-p", str(args.port), *identity_options, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
           "-o", "UserKnownHostsFile=" + str(args.known_hosts.resolve()), args.user + "@" + args.host]
    def remote(command, **kwargs):
        subprocess.run(ssh + [command], check=True, **kwargs)
    target = args.release_id
    script = args.root + "/bin/compose_release.py"
    if args.action == "deploy":
        manifest = json.loads((args.bundle / "release.json").read_text())
        target = manifest["id"]
        if not re.fullmatch(r"[a-f0-9]{40}-[0-9]+", target): parser.error("Invalid release ID")
        incoming = args.root + "/releases/.incoming-" + uuid.uuid4().hex
        final = args.root + "/releases/" + target
        temporary_script = args.root + "/bin/.publisher-" + uuid.uuid4().hex
        q = shlex.quote
        # Never copy .env or registry credentials into a release archive.
        with tempfile.TemporaryFile() as archive:
            with tarfile.open(fileobj=archive, mode="w") as tar:
                for name in ("release.json", "compose.json", "compose_release.py", "kubernetes.json"):
                    path = args.bundle / name
                    if not path.is_file() or path.is_symlink(): parser.error("Invalid bundle file: " + name)
                    tar.add(path, arcname=name)
            archive.seek(0)
            remote("set -eu; test -f " + q(args.root + "/.env") + "; test ! -e " + q(final) +
                   "; mkdir -p " + q(incoming) + " " + q(args.root + "/bin") +
                   "; tar -xf - -C " + q(incoming) + "; mv " + q(incoming) + " " + q(final) +
                   "; cp " + q(final + "/compose_release.py") + " " + q(temporary_script) +
                   "; mv " + q(temporary_script) + " " + q(script), stdin=archive)
    elif target and not re.fullmatch(r"[a-f0-9]{40}-[0-9]+", target):
        parser.error("Invalid rollback release ID")
    command = ["python3", script, args.action, "--root", args.root]
    if target: command += ["--release-id", target]
    remote(shlex.join(command))

if __name__ == "__main__":
    main()
