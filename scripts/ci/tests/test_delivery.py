import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/cd"))
import release
import render_k8s
import compose_release

def manifest(char="a", build=1):
    revision = char * 40
    return {"schemaVersion": 1, "id": revision + "-" + str(build), "revision": revision,
            "createdAt": "2026-09-15T00:00:00Z", "images": {
                role: "ghcr.io/test/tradepass-" + role + "@sha256:" + char * 64 for role in release.ROLES}}

class BundleTest(unittest.TestCase):
    def test_mutable_or_incomplete_releases_are_rejected(self):
        data = manifest()
        data["images"]["trade"] = "ghcr.io/test/tradepass-trade:latest"
        with self.assertRaises(ValueError): release.validate_release(data)
        del data["images"]["trade"]
        with self.assertRaises(ValueError): release.validate_release(data)

    def test_commit_and_release_id_must_match(self):
        data = manifest()
        data["revision"] = "b" * 40
        with self.assertRaises(ValueError): release.validate_release(data)

    def test_registry_prefix_rejects_shell_syntax(self):
        for prefix in ("https://ghcr.io/org/app", "ghcr.io/org/app;id", "ghcr.io/ORG/app", "x\nother/y"):
            with self.assertRaises(ValueError): release.validate_prefix(prefix)
        self.assertEqual("ghcr.io/org/tradepass", release.validate_prefix("ghcr.io/org/tradepass"))

    def test_release_bundle_has_no_build_or_database_access_in_file_process(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "release"
            release.bundle(manifest(), destination)
            self.assertEqual({"release.json", "compose.json", "compose_release.py", "kubernetes.json"}, {p.name for p in destination.iterdir()})
            compose = json.loads((destination / "compose.json").read_text())
            for role, service in compose["services"].items():
                self.assertNotIn("build", service)
                self.assertIn("@sha256:", service["image"])
                if role != "gateway": self.assertNotIn("ports", service)
            self.assertNotIn("DB_PASSWORD", compose["services"]["file"]["environment"])
            self.assertNotIn("TRADEPASS_DATABASE_URL", compose["services"]["file"]["environment"])

    def test_k8s_probes_ids_and_private_services(self):
        items = render_k8s.render(manifest())["items"]
        deployments = [i for i in items if i["kind"] in ("Deployment", "StatefulSet")]
        self.assertEqual(6, len(deployments))
        workers = []
        for deployment in deployments:
            spec = deployment["spec"]
            self.assertEqual(1, spec["replicas"])
            if deployment["metadata"]["name"] == "gateway": self.assertEqual("Recreate", spec["strategy"]["type"])
            else:
                self.assertEqual("StatefulSet", deployment["kind"])
                self.assertEqual("RollingUpdate", spec["updateStrategy"]["type"])
            pod = spec["template"]["spec"]
            self.assertFalse(pod["automountServiceAccountToken"])
            container = pod["containers"][0]
            self.assertEqual("/actuator/health/readiness", container["readinessProbe"]["httpGet"]["path"])
            for env in container["env"]:
                if env["name"] == "TRADEPASS_IDS_WORKER_BASE": workers.append(env["value"])
            if deployment["metadata"]["name"] in ("file", "gateway"):
                self.assertNotIn({"secretRef": {"name": "tradepass-business"}}, container["envFrom"])
        self.assertEqual(5, len(set(workers)))
        self.assertTrue(all(i["spec"].get("type", "ClusterIP") == "ClusterIP" for i in items if i["kind"] == "Service"))
        self.assertFalse(any(i["kind"] == "Secret" for i in items))

    def test_k8s_config_versions_change_and_production_is_not_implicitly_enabled(self):
        a = render_k8s.render(manifest(), datacenter=1)["items"][0]["metadata"]["name"]
        b = render_k8s.render(manifest(), datacenter=2)["items"][0]["metadata"]["name"]
        self.assertNotEqual(a, b)
        with self.assertRaises(ValueError): render_k8s.render(manifest(), namespace="production")
        with self.assertRaises(ValueError): render_k8s.render(manifest(), datacenter=32)

class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        settings = "TRADEPASS_ENVIRONMENT=staging\nTRADEPASS_INTERNAL_KEY=" + "x" * 32 + "\nTRADEPASS_IDS_DATACENTER_ID=2\nSEATA_SERVER_ADDR=seata:8091\nXXL_JOB_ACCESS_TOKEN=" + "t" * 32 + "\n"
        for role in ("identity", "contract", "trade", "settlement"):
            prefix = role.upper()
            settings += f"{prefix}_DATABASE_URL=jdbc:mysql://test/staging_{role}\n{prefix}_DB_USERNAME=test_{role}\n{prefix}_DB_PASSWORD=test-only-{role}\n"
        (self.root / ".env").write_text(settings)
        self.old, self.new = manifest("a"), manifest("b", 2)
        for data in (self.old, self.new):
            folder = self.root / "releases" / data["id"]
            folder.mkdir(parents=True)
            (folder / "release.json").write_text(json.dumps(data))
            (folder / "compose.json").write_text(json.dumps(release.compose_document(data)))
        compose_release.write_pointer(self.root, "current", self.old["id"])
        self.events = []

    def runner(self, command, **kwargs):
        self.events.append((command, kwargs))

    def action_events(self):
        events = []
        for command, kwargs in self.events:
            path = Path(command[command.index("-f") + 1])
            action = command[command.index("-f") + 2]
            events.append((path.parent.name, action))
        return events

    def test_success_pulls_before_stopping_and_records_previous_version(self):
        compose_release.Publisher(self.root, "tradepass-staging", self.runner).activate(self.new["id"])
        self.assertEqual([(self.new["id"], "config"), (self.new["id"], "pull"), (self.old["id"], "stop"), (self.new["id"], "up")], self.action_events())
        self.assertEqual(self.new["id"], compose_release.read_pointer(self.root, "current"))
        self.assertEqual(self.old["id"], compose_release.read_pointer(self.root, "previous"))

    def test_unhealthy_release_stops_new_writers_then_restores_old(self):
        def runner(command, **kwargs):
            self.runner(command, **kwargs)
            if self.new["id"] in " ".join(command) and "up" in command: raise subprocess.CalledProcessError(1, command)
        with self.assertRaises(subprocess.CalledProcessError):
            compose_release.Publisher(self.root, "tradepass-staging", runner).activate(self.new["id"])
        self.assertEqual([(self.new["id"], "stop"), (self.old["id"], "up")], self.action_events()[-2:])
        self.assertEqual(self.old["id"], compose_release.read_pointer(self.root, "current"))

    def test_pull_failure_does_not_stop_the_existing_release(self):
        def runner(command, **kwargs):
            self.runner(command, **kwargs)
            if "pull" in command: raise subprocess.CalledProcessError(1, command)
        with self.assertRaises(subprocess.CalledProcessError):
            compose_release.Publisher(self.root, "tradepass-staging", runner).activate(self.new["id"])
        self.assertFalse(any("stop" in command for command, _ in self.events))

    def test_interrupted_publication_restores_previous_release(self):
        def runner(command, **kwargs):
            self.runner(command, **kwargs)
            if self.new["id"] in " ".join(command) and "up" in command: raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            compose_release.Publisher(self.root, "tradepass-staging", runner).activate(self.new["id"])
        self.assertEqual([(self.new["id"], "stop"), (self.old["id"], "up")], self.action_events()[-2:])
        self.assertEqual(self.old["id"], compose_release.read_pointer(self.root, "current"))

    def test_first_failure_stops_containers_without_deleting_data(self):
        (self.root / "current").unlink()
        def runner(command, **kwargs):
            self.runner(command, **kwargs)
            if "up" in command: raise subprocess.CalledProcessError(1, command)
        with self.assertRaises(subprocess.CalledProcessError):
            compose_release.Publisher(self.root, "tradepass-staging", runner).activate(self.new["id"])
        self.assertIsNone(compose_release.read_pointer(self.root, "current"))
        self.assertEqual("stop", self.action_events()[-1][1])
        self.assertFalse(any("down" in command or "--volumes" in command for command, _ in self.events))

    def test_rollback_stop_failure_never_starts_another_writer(self):
        def runner(command, **kwargs):
            self.runner(command, **kwargs)
            if self.new["id"] in " ".join(command) and ("up" in command or "stop" in command):
                raise subprocess.CalledProcessError(1, command)
        with self.assertRaises(subprocess.CalledProcessError):
            compose_release.Publisher(self.root, "tradepass-staging", runner).activate(self.new["id"])
        self.assertNotIn((self.old["id"], "up"), self.action_events())

    def test_ci_environment_cannot_override_server_database(self):
        with patch.dict(os.environ, {"DB_PASSWORD": "wrong", "TRADEPASS_DATABASE_URL": "wrong", "DOCKER_CONFIG": "/tmp/registry-auth", "COMPOSE_REMOVE_ORPHANS": "true"}):
            compose_release.Publisher(self.root, "tradepass-staging", self.runner).activate(self.new["id"])
        for _, kwargs in self.events:
            self.assertNotIn("DB_PASSWORD", kwargs["env"])
            self.assertNotIn("TRADEPASS_DATABASE_URL", kwargs["env"])
            self.assertNotIn("COMPOSE_REMOVE_ORPHANS", kwargs["env"])
            self.assertEqual("/tmp/registry-auth", kwargs["env"]["DOCKER_CONFIG"])

    def test_tampered_image_is_rejected_before_running_docker(self):
        file = self.root / "releases" / self.new["id"] / "compose.json"
        data = json.loads(file.read_text())
        data["services"]["trade"]["image"] = "unreviewed:latest"
        file.write_text(json.dumps(data))
        with self.assertRaises(ValueError): compose_release.Publisher(self.root, "tradepass-staging", self.runner).activate(self.new["id"])
        self.assertEqual([], self.events)

    def test_production_settings_require_separate_acceptance(self):
        path = self.root / ".env"
        path.write_text(path.read_text().replace("ENVIRONMENT=staging", "ENVIRONMENT=production"))
        with self.assertRaises(ValueError): compose_release.Publisher(self.root, "tradepass-staging", self.runner)

if __name__ == "__main__":
    unittest.main()
