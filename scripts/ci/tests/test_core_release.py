"""Core deployment tests use a fake Docker runner, never a live daemon."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


apply = module("core_apply", "scripts/cd/core_apply.py")
ssh = module("core_ssh", "scripts/cd/core_ssh.py")
legacy_ssh = module("ssh_release", "scripts/cd/ssh_release.py")
setup = module("connect_core_jenkins", "scripts/server/connect-core-jenkins.py")
OLD = "sha256:" + "a" * 64
NEW = "sha256:" + "b" * 64


class PipelineOptionalParameterTest(unittest.TestCase):
    def execute_shell(self, script, optional):
        with tempfile.TemporaryDirectory() as directory:
            fake_python = Path(directory) / "python3"
            fake_python.write_text("#!" + sys.executable + "\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n")
            fake_python.chmod(0o755)
            env = {**os.environ, "PATH": directory + os.pathsep + os.defpath,
                   "ACTION": "status", "SERVICE": "all", "DEPLOY_HOST": "127.0.0.1",
                   "DEPLOY_USER": "root", "DEPLOY_PORT": "22", "DEPLOY_ROOT": "/opt/tradepass/staging",
                   "DEPLOY_SSH_KEY": "/private/key", "KNOWN_HOSTS_FILE": "/private/known_hosts"}
            env.pop("CORE_COMPOSE", None)
            env.pop("ROLLBACK_RELEASE", None)
            env.update(optional)
            result = subprocess.run(["bash", "-eu", "-c", script], env=env, capture_output=True, text=True, check=True)
            return json.loads(result.stdout)

    def test_core_shell_accepts_unset_empty_and_explicit_compose_path(self):
        pipeline = (ROOT / "Jenkinsfile.core").read_text()
        server = re.search(r"sh '''(.*?)'''", pipeline.split("stage('Server action')", 1)[1], re.S).group(1)
        preflight = re.search(r"sh '(python3 scripts/cd/core_ssh.py status[^']*)'", pipeline).group(1)
        for script in (server, preflight):
            for optional, expected in [({}, ""), ({"CORE_COMPOSE": ""}, ""),
                                       ({"CORE_COMPOSE": "/private/path with $literal/compose.yml"},
                                        "/private/path with $literal/compose.yml")]:
                with self.subTest(optional=optional, preflight=script == preflight):
                    args = self.execute_shell(script, optional)
                    self.assertEqual(expected, args[args.index("--compose") + 1])

    def test_legacy_rollback_uses_previous_release_when_parameter_is_missing(self):
        pipeline = (ROOT / "Jenkinsfile").read_text()
        script = re.search(r"sh '''(.*?)'''", pipeline.split("stage('Deploy or roll back staging')", 1)[1], re.S).group(1)
        args = self.execute_shell(script, {"ACTION": "rollback"})
        self.assertNotIn("--release-id", args)
        args = self.execute_shell(script, {"ACTION": "rollback", "ROLLBACK_RELEASE": "previous-id"})
        self.assertEqual("previous-id", args[args.index("--release-id") + 1])


def manifest(roles=("business",), delivery="local"):
    revision = "c" * 40
    return {"schema": 1, "revision": revision, "release": revision + "-3", "delivery": delivery,
            "sha256": "d" * 64 if delivery == "archive" else None,
            "images": {role: {"id": NEW, "tag": "tradepass-" + role + ":" + revision + "-3", "architecture": "amd64"}
                       for role in roles}}


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "core.compose.yml"
        self.config = {"name": "tradepass-core", "services": {
            role: {"image": "tradepass-" + role + ":local", "network_mode": "host",
                   "environment": {"LITERAL": "a$$b"}, "volumes": ["/private/bootstrap:/app/nacos-bootstrap.yml:ro"]}
            for role in apply.ROLES}}
        self.path.write_text(json.dumps(self.config))
        self.publisher = apply.Publisher(self.path)
        self.calls = []
        self.fail_up = 0
        self.patch = patch.object(apply, "run", side_effect=self.fake_run)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def fake_run(self, *command, **kwargs):
        self.calls.append(command)
        if command[:3] == ("docker", "image", "inspect"):
            return json.dumps([{"Id": command[3]}])
        if command[:2] == ("docker", "inspect"):
            role = command[-1].removeprefix("tradepass-core-").removesuffix("-1")
            doc = json.loads(self.path.read_text())
            image = doc["services"][role]["image"]
            return json.dumps([{"Image": image if image.startswith("sha256:") else OLD,
                                "Config": {"Labels": {"com.docker.compose.project": "tradepass-core", "com.docker.compose.service": role}},
                                "State": {"Status": "running", "Health": {"Status": "healthy"}}}])
        if "up" in command and self.fail_up:
            self.fail_up -= 1
            raise RuntimeError("mock failed health")
        return ""

    def test_single_service_publish_preserves_other_services_and_literal_secrets(self):
        self.publisher.activate({"business": NEW}, "release-1")
        config = json.loads(self.path.read_text())
        self.assertEqual(NEW, config["services"]["business"]["image"])
        self.assertEqual(self.config["services"]["identity"], config["services"]["identity"])
        self.assertEqual("a$$b", config["services"]["business"]["environment"]["LITERAL"])
        history = json.loads(self.publisher.history.read_text())
        self.assertEqual(OLD, history["business"]["previous"])
        self.assertFalse(self.publisher.journal.exists())
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        up = [c for c in self.calls if "up" in c]
        self.assertEqual(["business"], [c[-1] for c in up])
        self.assertIn("--no-deps", up[0])
        self.assertIn("--pull", up[0])
        self.assertIn("never", up[0])

    def test_all_stops_in_reverse_and_starts_in_dependency_order(self):
        self.publisher.activate({role: NEW for role in apply.ROLES}, "release-all")
        stop = next(c for c in self.calls if "stop" in c)
        self.assertEqual(("gateway", "business", "identity"), stop[-3:])
        self.assertEqual(list(apply.ROLES), [c[-1] for c in self.calls if "up" in c])

    def test_health_failure_restores_actual_old_image_and_original_history(self):
        self.fail_up = 1
        with self.assertRaisesRegex(RuntimeError, "failed health"):
            self.publisher.activate({"business": NEW}, "bad-release")
        self.assertEqual(OLD, json.loads(self.path.read_text())["services"]["business"]["image"])
        self.assertEqual({}, json.loads(self.publisher.history.read_text()))
        self.assertFalse(self.publisher.journal.exists())

    def test_failed_recovery_keeps_journal_and_blocks_new_deploy(self):
        self.fail_up = 2
        with self.assertRaises(RuntimeError):
            self.publisher.activate({"business": NEW}, "bad-release")
        self.assertTrue(self.publisher.journal.exists())
        with self.assertRaisesRegex(ValueError, "recover"):
            self.publisher.activate({"business": NEW}, "another-release")
        self.publisher.recover()
        self.assertFalse(self.publisher.journal.exists())

    def test_restart_preserves_previous_release_record(self):
        history = {"business": {"current": OLD, "previous": "sha256:" + "e" * 64, "release": "previous"}}
        apply.atomic(self.publisher.history, history)
        self.publisher.activate({"business": OLD}, "restart", record_history=False)
        self.assertEqual(history, json.loads(self.publisher.history.read_text()))

    def test_unknown_image_fails_before_stopping_or_editing_configuration(self):
        with self.assertRaisesRegex(ValueError, "镜像 ID"):
            self.publisher.activate({"business": "arbitrary:tag"}, "bad")
        self.assertEqual(self.config, json.loads(self.path.read_text()))
        self.assertFalse(any("stop" in c for c in self.calls))

    def test_old_six_service_configuration_is_rejected(self):
        config = copy.deepcopy(self.config)
        config["services"]["contract"] = {"image": "old"}
        self.path.write_text(json.dumps(config))
        with self.assertRaises(ValueError):
            self.publisher.activate({"business": NEW}, "bad")
        self.assertEqual([], self.calls)

    def test_invalid_compose_is_rejected_before_stopping_running_services(self):
        original = self.fake_run
        def reject_candidate(*command, **kwargs):
            if "config" in command:
                raise RuntimeError("invalid compose")
            return original(*command, **kwargs)
        with patch.object(apply, "run", side_effect=reject_candidate):
            with self.assertRaisesRegex(RuntimeError, "invalid compose"):
                self.publisher.activate({"business": NEW}, "bad")
        self.assertFalse(any("stop" in c for c in self.calls))
        self.assertFalse(self.publisher.journal.exists())
        self.assertEqual(self.config, json.loads(self.path.read_text()))

    def test_health_status_fails_if_container_is_unhealthy(self):
        with patch.object(self.publisher, "inspect", return_value={"Image": OLD, "State": {"Status": "running", "Health": {"Status": "unhealthy"}}}):
            self.assertEqual(1, self.publisher.status(["business"]))


class DeliveryTest(unittest.TestCase):
    def test_explicit_key_works_without_agent_and_keeps_host_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key with spaces"
            hosts = Path(directory) / "known_hosts"
            key.write_text("test key content")
            hosts.write_text("test host key")
            commands = [ssh.ssh_command("127.0.0.1", "root", 22, hosts, key)]
            argv = ["ssh_release.py", "rollback", "--host", "127.0.0.1", "--identity-file", str(key),
                    "--known-hosts", str(hosts)]
            with patch("sys.argv", argv), patch.object(legacy_ssh.subprocess, "run") as run:
                legacy_ssh.main()
                commands.append(run.call_args.args[0])
            for command in commands:
                self.assertEqual(str(key.resolve()), command[command.index("-i") + 1])
                self.assertIn("IdentityAgent=none", command)
                self.assertIn("IdentitiesOnly=yes", command)
                self.assertIn("StrictHostKeyChecking=yes", command)
                self.assertNotIn("test key content", " ".join(command))
            with self.assertRaisesRegex(ValueError, "私钥文件不存在"):
                ssh.ssh_command("127.0.0.1", "root", 22, hosts, Path(directory) / "missing")

    def test_manifest_requires_exact_selection_and_immutable_matching_images(self):
        apply.validate_manifest(manifest(), ["business"])
        with self.assertRaises(ValueError):
            apply.validate_manifest(manifest(), ["gateway"])
        for field, value in (("id", "latest"), ("tag", "arbitrary:tag")):
            invalid = manifest()
            invalid["images"]["business"][field] = value
            with self.assertRaises(ValueError):
                apply.validate_manifest(invalid, ["business"])

    def test_archive_checksum_failure_never_loads_images(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(apply, "run") as run:
            bundle = Path(directory)
            (bundle / "release.json").write_text(json.dumps(manifest(delivery="archive")))
            (bundle / "images.tar").write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "校验失败"):
                apply.load_images(bundle, ["business"])
            run.assert_not_called()

    def test_local_delivery_requires_matching_local_architecture_and_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory)
            value = manifest()
            (bundle / "release.json").write_text(json.dumps(value))
            info = {"Architecture": "amd64", "Os": "linux", "Config": {"Labels": {"org.opencontainers.image.revision": value["revision"]}}}
            with patch.object(apply, "run", side_effect=["x86_64", json.dumps([info])]) as run:
                self.assertEqual(value, apply.load_images(bundle, ["business"]))
                self.assertFalse(any("load" in c.args for c in run.call_args_list))
            info["Architecture"] = "arm64"
            with patch.object(apply, "run", side_effect=["x86_64", json.dumps([info])]):
                with self.assertRaisesRegex(ValueError, "架构"):
                    apply.load_images(bundle, ["business"])

    def test_ssh_rejects_option_injection_and_requires_verified_host_keys(self):
        with tempfile.NamedTemporaryFile() as hosts:
            args = ssh.ssh_command("124.221.190.63", "root", 22, Path(hosts.name))
            self.assertIn("StrictHostKeyChecking=yes", args)
            self.assertIn("BatchMode=yes", args)
            for host in ["-oProxyCommand=bad", "host;id", "host\ncommand"]:
                with self.assertRaises(ValueError):
                    ssh.ssh_command(host, "root", 22, Path(hosts.name))


class RuntimeSnapshotTest(unittest.TestCase):
    def fixture(self):
        return {"Id": "c" * 64, "Image": OLD,
                "Config": {"Env": ["NACOS_USERNAME=nacos", "NACOS_PASSWORD=literal$p${word}", "JAVA_OPTS=-Xmx256m"],
                           "User": "0:0", "WorkingDir": "/app", "Hostname": "c" * 12,
                           "Entrypoint": ["sh", "/app/entrypoint.sh"], "Cmd": None,
                           "Labels": {"com.docker.compose.project": "tradepass-core", "custom": "keep"},
                           "Healthcheck": {"Test": ["CMD-SHELL", "curl http://127.0.0.1:$MANAGEMENT_PORT/actuator/health/readiness"],
                                           "Interval": 15000000000, "Timeout": 5000000000, "StartPeriod": 90000000000, "Retries": 3}},
                "HostConfig": {"NetworkMode": "host", "Memory": 738197504, "MemorySwap": 1476395008,
                               "RestartPolicy": {"Name": "no"}, "NanoCpus": 1000000000,
                               "ReadonlyRootfs": False, "IpcMode": "private", "MemorySwappiness": 0,
                               "LogConfig": {"Type": "json-file", "Config": {"max-size": "10m"}}},
                "Mounts": [{"Type": "bind", "Source": "/docker/tradepass/logs", "Destination": "/root/logs",
                            "RW": True, "Mode": "rw", "Propagation": "rprivate"}]}

    def test_env_based_deployment_preserves_secrets_limits_and_only_existing_mounts(self):
        service = apply.snapshot_service("business", self.fixture())
        self.assertEqual("literal$$p$${word}", service["environment"]["NACOS_PASSWORD"])
        self.assertEqual(738197504, service["mem_limit"])
        self.assertEqual(0, service["mem_swappiness"])
        self.assertEqual(1, service["cpus"])
        self.assertNotIn("hostname", service)
        self.assertEqual(["/root/logs"], [mount["target"] for mount in service["volumes"]])
        self.assertEqual({"custom": "keep"}, service["labels"])
        self.assertIn("$$MANAGEMENT_PORT", service["healthcheck"]["test"][1])
        self.assertEqual("15000000000ns", service["healthcheck"]["interval"])

    def test_bootstrap_mount_is_retained_when_it_really_exists(self):
        data = self.fixture()
        data["Mounts"].append({"Type": "bind", "Source": "/private/bootstrap.yml",
                               "Destination": "/app/nacos-bootstrap.yml", "RW": False, "Mode": "ro"})
        service = apply.snapshot_service("identity", data)
        self.assertTrue(service["volumes"][1]["read_only"])
        self.assertFalse(service["volumes"][1]["bind"]["create_host_path"])

    def test_special_layouts_are_rejected_instead_of_silently_losing_configuration(self):
        for field, value in [("NetworkMode", "bridge"), ("Privileged", True), ("PidMode", "host"),
                             ("Tmpfs", {"/tmp": "rw"}), ("Devices", [{"PathOnHost": "/dev/sda"}])]:
            data = self.fixture()
            data["HostConfig"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                apply.snapshot_service("business", data)

    def test_missing_healthcheck_is_rejected_before_recreation(self):
        data = self.fixture()
        data["Config"].pop("Healthcheck")
        with self.assertRaisesRegex(ValueError, "健康检查"):
            apply.snapshot_service("business", data)

    def test_resnapshot_uses_current_environment_and_keeps_rollback_history(self):
        with tempfile.TemporaryDirectory() as directory:
            publisher = apply.Publisher(Path(directory) / "core.compose.yml")
            apply.atomic(publisher.history, {"business": {"previous": NEW}})
            data = self.fixture()
            with patch.object(publisher, "inspect", return_value=data), patch.object(publisher, "preflight"):
                publisher.snapshot()
                data["Config"]["Env"] = ["NACOS_PASSWORD=changed$secret"]
                publisher.snapshot()
            saved = json.loads(publisher.compose.read_text())
            self.assertEqual("changed$$secret", saved["services"]["business"]["environment"]["NACOS_PASSWORD"])
            self.assertEqual(NEW, json.loads(publisher.history.read_text())["business"]["previous"])

    def test_setup_payload_does_not_interpolate_credentials_into_groovy_source(self):
        secret = "quote'\\\n${variable}"
        source = setup.configure_script({"gitToken": secret})
        self.assertNotIn(secret, source)
        self.assertNotIn("// TP_CONFIG", source)
        self.assertLess(source.index("import "), source.index("def cfg ="))

    def test_setup_does_not_follow_redirects_with_credentials(self):
        with self.assertRaisesRegex(RuntimeError, "重定向"):
            setup.NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://different.example")


if __name__ == "__main__":
    unittest.main()
