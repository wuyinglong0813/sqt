#!/usr/bin/env bash
# Shared implementation for all.sh and the individual service entry points.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:?Use all.sh or a service-name.sh entry point}"
shift
ACTION="${1:-status}"
if [ "$#" -gt 0 ]; then shift; fi
case "$ACTION" in
  -h|--help)
    printf '用法：%s.sh [start|stop|restart|status|check] [--timeout 秒] [--stop-timeout 秒] [--dry-run]\n' "$TARGET"
    printf '不带参数默认查看状态；start 自动补齐依赖，stop 只停止指定范围。\n'
    exit 0
    ;;
  start|stop|restart|status|check) ;;
  *) printf '未知操作：%s，请运行 %s.sh --help\n' "$ACTION" "$TARGET" >&2; exit 2 ;;
esac
exec python3 "$SCRIPT_DIR/tradepass.py" "$ACTION" "$TARGET" "$@"
