#!/usr/bin/env python3
"""Generate a single shell entry point for connecting the already installed Jenkins."""
import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FILES = ["setup-core-jenkins.sh", "connect-core-jenkins.py"]


def main():
    output = ROOT / "dist/connect-tradepass-jenkins.sh"
    lines = ["#!/usr/bin/env bash", "set -euo pipefail", "set +x", "umask 077",
             'TP_SETUP_DIR=$(mktemp -d /tmp/tradepass-jenkins-connect.XXXXXXXX)',
             '''trap 'rm -rf -- "$TP_SETUP_DIR"' EXIT''']
    for name in FILES:
        encoded = base64.b64encode((ROOT / "scripts/server" / name).read_bytes()).decode()
        lines += ["base64 -d > \"$TP_SETUP_DIR/" + name + "\" <<'TRADEPASS_SOURCE'", encoded, "TRADEPASS_SOURCE"]
    lines += ['python3 "$TP_SETUP_DIR/connect-core-jenkins.py" "$@"']
    output.parent.mkdir(exist_ok=True)
    output.write_text("\n".join(lines) + "\n")
    output.chmod(0o755)
    print(output)


if __name__ == "__main__":
    main()
