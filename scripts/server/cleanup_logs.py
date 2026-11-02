#!/usr/bin/env python3
"""Clean only old TradePass file-log archives; never touch Docker-managed logs."""
import argparse
from datetime import date, datetime, timedelta
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import sys
import time


ARCHIVE = re.compile(r"tradepass-(identity|business|gateway)\.log\.(\d{4}-\d{2}-\d{2})\.(\d+)\.log(?:\.gz)?\Z")


def signature(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def inventory(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Log directory must be an existing real directory")
    entries = []
    with os.scandir(directory) as files:
        for item in files:
            match = ARCHIVE.fullmatch(item.name)
            if not match or not item.is_file(follow_symlinks=False):
                continue
            try:
                day = date.fromisoformat(match.group(2))
            except ValueError:
                continue
            info = item.stat(follow_symlinks=False)
            if not stat.S_ISREG(info.st_mode):
                continue
            entries.append({"name": item.name, "role": match.group(1), "day": day,
                            "sequence": int(match.group(3)), "size": info.st_size,
                            "mtime": info.st_mtime, "signature": signature(info)})
    return entries


def plan(entries, now, retention_days=30, archive_cap_bytes=1024 ** 3):
    today = datetime.fromtimestamp(now).date()
    cutoff_day = today - timedelta(days=retention_days)
    cutoff_time = now - retention_days * 86400
    selected = {}
    totals = {}
    # A recently modified old-name archive may still be a restored or live file.
    # Require both its filename date and mtime to be old before expiring it.
    for item in entries:
        if item["day"] < cutoff_day and item["mtime"] < cutoff_time:
            selected[item["name"]] = {**item, "reason": "retention"}
    for role in ("identity", "business", "gateway"):
        archives = [item for item in entries if item["role"] == role]
        total = sum(item["size"] for item in archives if item["name"] not in selected)
        # Keep today's archives, future-dated files and anything modified today.
        # This also avoids racing the appender that is currently rolling files.
        candidates = sorted((item for item in archives if item["name"] not in selected
                             and item["day"] < today
                             and datetime.fromtimestamp(item["mtime"]).date() < today),
                            key=lambda item: (item["day"], item["sequence"], item["name"]))
        for item in candidates:
            if total <= archive_cap_bytes:
                break
            selected[item["name"]] = {**item, "reason": "archive_cap"}
            total -= item["size"]
        totals[role] = total
    return sorted(selected.values(), key=lambda item: item["name"]), totals


def remove_unchanged(directory, candidates):
    removed, skipped = [], []
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(directory, flags)
    try:
        for item in candidates:
            # Revalidate the exact whitelist and file identity immediately before
            # unlinking. Never follow a symlink or delete a newer replacement.
            if not ARCHIVE.fullmatch(item["name"]):
                raise ValueError("Unexpected archive filename")
            try:
                current = os.stat(item["name"], dir_fd=fd, follow_symlinks=False)
                if not stat.S_ISREG(current.st_mode) or signature(current) != item["signature"]:
                    skipped.append(item["name"])
                    continue
                os.unlink(item["name"], dir_fd=fd)
            except FileNotFoundError:
                skipped.append(item["name"])
                continue
            removed.append(item["name"])
    finally:
        os.close(fd)
    return removed, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("/docker/tradepass/logs"))
    parser.add_argument("--retention-days", type=int, default=30)
    parser.add_argument("--archive-cap-mib", type=int, default=1024)
    parser.add_argument("--apply", action="store_true", help="Delete planned archives; default is read-only")
    args = parser.parse_args(argv)
    if args.retention_days < 1 or args.archive_cap_mib < 10:
        parser.error("retention-days must be positive and archive-cap-mib must be at least 10")
    lock = None
    try:
        if args.directory.is_symlink() or not args.directory.is_dir():
            raise ValueError("Log directory must be an existing real directory")
        if args.apply:
            flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
            lock = os.open(args.directory / ".tradepass-log-cleanup.lock", flags, 0o600)
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print(json.dumps({"status": "skipped", "reason": "cleanup already running"}))
                return 0
        candidates, totals = plan(inventory(args.directory), time.time(), args.retention_days,
                                 args.archive_cap_mib * 1024 ** 2)
        report = {"status": "applied" if args.apply else "dry-run", "directory": str(args.directory),
                  "planned": [{"name": item["name"], "bytes": item["size"], "reason": item["reason"]}
                              for item in candidates],
                  "archive_bytes_after_plan": totals,
                  "cap_exceeded_by_protected_archives": [role for role, size in totals.items()
                                                         if size > args.archive_cap_mib * 1024 ** 2]}
        if args.apply:
            removed, skipped = remove_unchanged(args.directory, candidates)
            report.update(removed=removed, skipped_changed=skipped,
                          freed_bytes=sum(item["size"] for item in candidates if item["name"] in removed))
        print(json.dumps(report, ensure_ascii=False))
        return 0
    except (OSError, ValueError) as error:
        print(json.dumps({"status": "error", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    finally:
        if lock is not None:
            os.close(lock)


if __name__ == "__main__":
    sys.exit(main())
