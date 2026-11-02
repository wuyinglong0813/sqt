"""Exercise archive deletion against temporary files, never live service logs."""
from datetime import datetime, timedelta
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/server/cleanup_logs.py"
spec = importlib.util.spec_from_file_location("cleanup_logs", SCRIPT)
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class LogArchiveCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.now = datetime.now().replace(hour=12, minute=0, second=0, microsecond=0)

    def archive(self, role="business", days=40, sequence=0, size=4, mtime_days=None):
        day = (self.now - timedelta(days=days)).date()
        p = self.directory / f"tradepass-{role}.log.{day}.{sequence}.log"
        p.write_bytes(b"x" * size)
        stamp = (self.now - timedelta(days=days if mtime_days is None else mtime_days)).timestamp()
        os.utime(p, (stamp, stamp))
        return p

    def test_expiry_keeps_live_logs_unrelated_files_symlinks_and_recent_modifications(self):
        old = self.archive()
        recent = self.archive(days=2)
        restored = self.archive(days=41, mtime_days=0)
        active = self.directory / "tradepass-business.log"
        active.write_text("active")
        unrelated = self.directory / "mysql.log.2020-01-01.0.log"
        unrelated.write_text("database")
        link = self.directory / "tradepass-identity.log.2020-01-01.0.log"
        link.symlink_to(active)
        invalid = self.directory / "tradepass-gateway.log.2020-99-99.0.log"
        invalid.write_text("invalid")
        planned, _ = cleanup.plan(cleanup.inventory(self.directory), self.now.timestamp())
        self.assertEqual([old.name], [item["name"] for item in planned])
        self.assertEqual(([old.name], []), cleanup.remove_unchanged(self.directory, planned))
        for p in [recent, restored, active, unrelated, link, invalid]:
            self.assertTrue(p.exists(), p.name)

    def test_capacity_is_per_service_and_preserves_today(self):
        older = self.archive(days=3, size=8)
        newer = self.archive(days=2, size=8)
        today = self.archive(days=0, size=8)
        identity = self.archive(role="identity", days=3, size=8)
        planned, totals = cleanup.plan(cleanup.inventory(self.directory), self.now.timestamp(),
                                       archive_cap_bytes=16)
        self.assertEqual([older.name], [item["name"] for item in planned])
        self.assertEqual("archive_cap", planned[0]["reason"])
        self.assertEqual(16, totals["business"])
        self.assertEqual(8, totals["identity"])
        cleanup.remove_unchanged(self.directory, planned)
        for p in [newer, today, identity]:
            self.assertTrue(p.exists())

    def test_large_current_day_archive_is_kept_and_reported_over_cap(self):
        today = self.archive(days=0, size=20)
        planned, totals = cleanup.plan(cleanup.inventory(self.directory), self.now.timestamp(),
                                       archive_cap_bytes=16)
        self.assertEqual([], planned)
        self.assertEqual(20, totals["business"])
        self.assertTrue(today.exists())

    def test_changed_archive_and_replaced_symlink_are_skipped(self):
        changed = self.archive()
        replaced = self.archive(role="identity")
        planned, _ = cleanup.plan(cleanup.inventory(self.directory), self.now.timestamp())
        changed.write_text("changed since inventory")
        replaced.unlink()
        replaced.symlink_to(changed)
        removed, skipped = cleanup.remove_unchanged(self.directory, planned)
        self.assertEqual([], removed)
        self.assertEqual({changed.name, replaced.name}, set(skipped))
        self.assertTrue(changed.exists())
        self.assertTrue(replaced.is_symlink())

    def test_cli_is_dry_run_by_default_and_apply_preserves_active_log(self):
        old = self.archive(days=60)
        active = self.directory / "tradepass-business.log"
        active.write_text("active")
        result = subprocess.run([sys.executable, str(SCRIPT), "--directory", str(self.directory)],
                                capture_output=True, text=True, check=True)
        self.assertEqual("dry-run", json.loads(result.stdout)["status"])
        self.assertTrue(old.exists())
        result = subprocess.run([sys.executable, str(SCRIPT), "--directory", str(self.directory), "--apply"],
                                capture_output=True, text=True, check=True)
        self.assertEqual([old.name], json.loads(result.stdout)["removed"])
        self.assertFalse(old.exists())
        self.assertEqual("active", active.read_text())

    def test_symlink_directory_is_rejected_before_deletion(self):
        self.archive()
        with tempfile.TemporaryDirectory() as temp:
            alias = Path(temp) / "alias"
            alias.symlink_to(self.directory, target_is_directory=True)
            with self.assertRaises(ValueError):
                cleanup.inventory(alias)


if __name__ == "__main__":
    unittest.main()
