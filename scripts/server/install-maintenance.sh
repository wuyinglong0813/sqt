#!/usr/bin/env bash
set -euo pipefail

if [[ "$(id -u)" != 0 ]]; then
  echo 'Run this installer as root on the existing TradePass server.' >&2
  exit 2
fi
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
UNIT_SOURCE="$ROOT_DIR/deploy/server/maintenance"
for command in python3 docker systemctl systemd-analyze journalctl; do
  command -v "$command" >/dev/null
done
for script in maintenance.py cleanup_logs.py; do
  test -f "$SCRIPT_DIR/$script"
done
for unit in tradepass-maintenance.service tradepass-maintenance.timer tradepass-log-cleanup.service tradepass-log-cleanup.timer; do
  test -f "$UNIT_SOURCE/$unit"
done

install -d -m 0700 /usr/local/lib/tradepass-maintenance /var/lib/tradepass-maintenance
BACKUP_DIR="/var/lib/tradepass-maintenance/install-backup-$(date +%Y%m%d-%H%M%S)-$$"
install -d -m 0700 "$BACKUP_DIR"
for script in maintenance.py cleanup_logs.py; do
  if [[ -f "/usr/local/lib/tradepass-maintenance/$script" ]]; then
    cp -p -- "/usr/local/lib/tradepass-maintenance/$script" "$BACKUP_DIR/$script"
  fi
  install -m 0755 -- "$SCRIPT_DIR/$script" "/usr/local/lib/tradepass-maintenance/$script"
done
for unit in tradepass-maintenance.service tradepass-maintenance.timer tradepass-log-cleanup.service tradepass-log-cleanup.timer; do
  if [[ -e "/etc/systemd/system/$unit" ]]; then
    cp -p -- "/etc/systemd/system/$unit" "$BACKUP_DIR/$unit"
  fi
  install -m 0644 -- "$UNIT_SOURCE/$unit" "/etc/systemd/system/$unit"
done
systemd-analyze verify /etc/systemd/system/tradepass-maintenance.service /etc/systemd/system/tradepass-maintenance.timer /etc/systemd/system/tradepass-log-cleanup.service /etc/systemd/system/tradepass-log-cleanup.timer
systemctl daemon-reload
systemctl enable --now tradepass-maintenance.timer tradepass-log-cleanup.timer
if systemctl cat logrotate.timer >/dev/null 2>&1; then
  systemctl enable --now logrotate.timer
fi
echo "Maintenance timers installed. Previous files, if any: $BACKUP_DIR"
echo 'No application containers or Docker daemon were restarted.'
