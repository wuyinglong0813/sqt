#!/usr/bin/env bash
# Run inside a service container as its existing UID. The approved boot JAR is supplied locally.
set -euo pipefail
: "${ARTHAS_BOOT_JAR:?Supply the verified arthas-boot.jar path}"
: "${ARTHAS_BOOT_SHA256:?Supply the release SHA-256 from your artifact repository}"
[[ "$ARTHAS_BOOT_SHA256" =~ ^[a-f0-9]{64}$ ]] || exit 1
printf '%s  %s\n' "$ARTHAS_BOOT_SHA256" "$ARTHAS_BOOT_JAR" | sha256sum --check --status
exec java -Duser.home=/tmp -jar "$ARTHAS_BOOT_JAR" --target-ip 127.0.0.1 --telnet-port 3658 --http-port -1 1
