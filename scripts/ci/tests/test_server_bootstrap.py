import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("server_services", ROOT / "scripts/server/services.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "server"

    def test_rerun_preserves_passwords_and_operator_configuration(self):
        values = server.prepare(self.root)
        stage = self.root / "staging/.env"
        stage.write_text(stage.read_text() + "# operator changes\nWECHAT_APP_ID=private-app\n")
        monitoring = self.root / "infra/monitoring/prometheus.yml"
        monitoring.write_text("# operator maintained scrape configuration\n")
        snapshots = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(values, server.prepare(self.root))
        self.assertTrue(all(path.read_bytes() == data for path, data in snapshots.items()))

    def test_secret_permissions_work_even_under_restrictive_umask(self):
        mask = os.umask(0o077)
        try: server.prepare(self.root)
        finally: os.umask(mask)
        self.assertEqual(0o600, (self.root / "infra/.env").stat().st_mode & 0o777)
        self.assertEqual(0o700, (self.root / "infra").stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.root / "staging/.env").stat().st_mode & 0o777)
        self.assertEqual(0o644, (self.root / "infra/secrets/grafana-password").stat().st_mode & 0o777)
        self.assertEqual(0o644, (self.root / "infra/monitoring/prometheus.yml").stat().st_mode & 0o777)
        self.assertEqual(0o755, (self.root / "infra/monitoring/grafana/datasources").stat().st_mode & 0o777)

    def test_no_database_ports_and_all_web_interfaces_loopback(self):
        values = server.prepare(self.root)
        compose = json.loads((self.root / "infra/compose.json").read_text())
        for name in ("mysql", "redis", "node-exporter"):
            self.assertNotIn("ports", compose["services"][name])
        for service in compose["services"].values():
            self.assertTrue(all(p.startswith("127.0.0.1:") for p in service.get("ports", [])))
            self.assertNotIn("/var/run/docker.sock", str(service))
        stage = server.read_env(self.root / "staging/.env")
        self.assertEqual(compose["networks"]["services"]["name"], stage["TRADEPASS_NETWORK_NAME"])
        self.assertEqual("true", stage["TRADEPASS_NETWORK_EXTERNAL"])
        self.assertNotIn("TRADEPASS_DATABASE_URL", stage)
        passwords = []
        for role in ("identity", "contract", "trade", "settlement"):
            prefix = role.upper()
            self.assertIn("mysql:3306/tradepass_staging_" + role, stage[prefix + "_DATABASE_URL"])
            self.assertEqual(values[prefix + "_DB_PASSWORD"], stage[prefix + "_DB_PASSWORD"])
            self.assertNotIn(values[prefix + "_DB_PASSWORD"], json.dumps(compose))
            passwords.append(stage[prefix + "_DB_PASSWORD"])
        self.assertEqual(4, len(set(passwords)))
        self.assertIn("seata", compose["services"])
        self.assertIn("rocketmq-topic-init", compose["services"])
        self.assertIn("xxl-job-admin", compose["services"])
        self.assertEqual("false", stage["TRADEPASS_REDIS_ENABLED"])

    def test_config_and_password_mounts_support_selinux_without_relabeling_host(self):
        server.prepare(self.root)
        compose = json.loads((self.root / "infra/compose.json").read_text())
        mounted_secrets = set()
        for service in compose["services"].values():
            self.assertNotIn("label=disable", service.get("security_opt", []))
            for mount in service.get("volumes", []):
                if not isinstance(mount, dict) or mount.get("type") != "bind": continue
                if mount["source"] == "/":
                    self.assertNotIn("selinux", mount.get("bind", {}))
                    continue
                self.assertTrue(mount["source"].startswith("./"))
                self.assertTrue(mount["read_only"])
                self.assertEqual("z", mount["bind"]["selinux"])
                self.assertFalse(mount["bind"]["create_host_path"])
                self.assertTrue((self.root / "infra" / mount["source"]).exists())
                if mount["target"].startswith("/run/secrets/"):
                    mounted_secrets.add(mount["target"].removeprefix("/run/secrets/"))
        self.assertEqual({"mysql-root", "redis-password", "grafana-password"}, mounted_secrets)

    def test_lost_secrets_and_changed_installation_are_rejected(self):
        server.prepare(self.root)
        with self.assertRaises(ValueError): server.prepare(self.root, network="tradepass-other")
        (self.root / "infra/.env").unlink()
        with self.assertRaisesRegex(ValueError, "lost its .env"): server.prepare(self.root)

    def test_inconsistent_secret_file_is_never_silently_replaced(self):
        server.prepare(self.root)
        path = self.root / "infra/secrets/mysql-root"
        path.write_text("original-volume-password\n")
        with self.assertRaisesRegex(ValueError, "Secret mismatch"): server.prepare(self.root)
        self.assertEqual("original-volume-password\n", path.read_text())

    def test_old_volumes_cannot_be_adopted_with_new_or_missing_credentials(self):
        def docker(*args, **kwargs):
            if args[:2] == ("volume", "ls"): return "tradepass-infra_mysql-data"
            if args[:2] == ("volume", "inspect"): return '[{"Labels": {}}]'
            self.fail("Unexpected Docker mutation: " + str(args))
        with patch.object(server, "docker", docker):
            for installation in (None, "new-install"):
                with self.assertRaisesRegex(ValueError, "different installation"):
                    server.check_existing_volumes("tradepass-infra", installation)

    def test_start_pins_images_and_rerun_preserves_digests(self):
        values = server.prepare(self.root)
        calls = []
        network_exists = [False]
        def docker(*args, **kwargs):
            calls.append(args)
            if args[:2] == ("volume", "ls"): return ""
            if args[:2] == ("network", "ls"): return values["DOCKER_NETWORK"] if network_exists[0] else ""
            if args[:2] == ("network", "create"): network_exists[0] = True; return ""
            if args[:2] == ("network", "inspect"):
                return json.dumps([{"Labels": {"tradepass.installation": values["INSTALLATION_ID"]}}])
            if "config" in args and "--format" in args:
                compose = json.loads((self.root / "infra/compose.json").read_text())
                lock = self.root / "infra/images.lock.json"
                if lock.exists():
                    for name, service in json.loads(lock.read_text())["services"].items():
                        compose["services"][name].update(service)
                return json.dumps(compose)
            if args[:2] == ("image", "inspect"):
                return json.dumps([args[-1].split("@")[0].split(":")[0] + "@sha256:" + "a" * 64])
            return ""
        with patch.object(server, "docker", docker):
            server.start(self.root, values, jenkins=True, monitoring=True, nginx=True)
            locked = (self.root / "infra/images.lock.json").read_bytes()
            server.start(self.root, values, jenkins=True, monitoring=True, nginx=True)
        self.assertEqual(locked, (self.root / "infra/images.lock.json").read_bytes())
        first_pull = next(i for i, args in enumerate(calls) if "pull" in args)
        first_up = next(i for i, args in enumerate(calls) if "up" in args)
        self.assertLess(first_pull, first_up)
        self.assertIn("edge", calls[first_pull])
        self.assertIn("jenkins", calls[first_pull])
        self.assertIn("monitoring", calls[first_pull])
        self.assertIn("--wait", calls[first_up])
        self.assertIn("never", calls[first_up])
        self.assertFalse(any("down" in args or "prune" in args for args in calls))

    def test_remote_docker_environment_is_not_inherited(self):
        with patch.dict(os.environ, {"DOCKER_HOST": "tcp://remote:2375", "COMPOSE_PROFILES": "all"}):
            env = server.docker_environment()
        self.assertEqual("unix:///var/run/docker.sock", env["DOCKER_HOST"])
        self.assertNotIn("COMPOSE_PROFILES", env)

    def test_symlink_cannot_overwrite_another_configuration(self):
        self.root.mkdir()
        target = self.root / "original.env"
        target.write_text("KEEP\n")
        infra = self.root / "infra"
        infra.mkdir()
        (infra / ".env").symlink_to(target)
        with self.assertRaisesRegex(ValueError, "symlink"): server.prepare(self.root)
        self.assertEqual("KEEP\n", target.read_text())


if __name__ == "__main__": unittest.main()
