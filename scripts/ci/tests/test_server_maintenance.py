"""Protect current/rollback/container images while exercising destructive maintenance boundaries."""
import contextlib
import copy
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("server_maintenance", ROOT / "scripts/server/maintenance.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def image_id(number):
    return "sha256:" + format(number, "064x")


def image(number, role="business", age=72, tags=None):
    return {"Id": image_id(number), "RepoTags": tags if tags is not None else [
        "tradepass-" + role + ":" + "a" * 40 + "-" + str(number)],
        "Created": (datetime.now(timezone.utc) - timedelta(hours=age)).isoformat(), "RepoDigests": []}


class FakeDocker:
    def __init__(self):
        self.history = {}
        self.containers = []
        self.images = []
        self.commands = []
        for index, role in enumerate(mod.ROLES):
            current, previous = 10 + index, 20 + index
            self.history[role] = {"current": image_id(current), "previous": image_id(previous), "release": "test"}
            self.images.extend([image(current, role), image(previous, role)])
            self.containers.append({"Name": "/tradepass-core-" + role + "-1", "Image": image_id(current),
                                    "State": {"Status": "running", "Health": {"Status": "healthy"}},
                                    "Config": {"Labels": {"com.docker.compose.project": "tradepass-core",
                                                          "com.docker.compose.service": role}}})
        self.images.append(image(30))

    def check_local(self):
        pass

    def inspect_all(self, kind):
        return copy.deepcopy(self.containers if kind == "container" else self.images)

    def cache_command(self, hours, size):
        return ["builder", "prune", "--force", "--filter", "until=" + str(hours) + "h", "--max-used-space", size]

    def run(self, *args, **kwargs):
        self.commands.append(args)
        if args[:2] == ("image", "rm"):
            self.images = [row for row in self.images if row["Id"] != args[2]]
        return "Total: 1GB"


class MaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.docker = FakeDocker()
        self.write_history()
        self.output = io.StringIO()
        output_context = contextlib.redirect_stdout(self.output)
        output_context.__enter__()
        self.addCleanup(output_context.__exit__, None, None, None)

    def write_history(self):
        (self.directory / "history.json").write_text(json.dumps(self.docker.history))

    def run_maintenance(self, **kwargs):
        return mod.maintain(self.docker, self.directory, idle_check=lambda: None, **kwargs)

    def test_plan_protects_running_rollback_stopped_external_and_recent_images(self):
        self.docker.images.extend([
            image(31, age=1), image(32, tags=["mysql:8.4"]),
            image(33, tags=["tradepass-business:" + "a" * 40 + "-33", "other-app:keep"]),
            image(34, tags=[]), image(35), image(36),
        ])
        self.docker.images[-1]["RepoDigests"] = ["registry.example/app@" + image_id(36)]
        self.docker.images.append(image(37, tags=["tradepass-business:" + "a" * 40 + "-37",
                                                "tradepass-business:" + "b" * 40 + "-38"]))
        self.docker.containers.append({"Name": "/backup", "Image": image_id(35), "State": {"Status": "exited"}})
        result = self.run_maintenance()
        self.assertEqual({"status": "dry-run", "remove_count": 1}, result)
        plan = json.loads(self.output.getvalue().splitlines()[0])
        self.assertEqual([image_id(30)], [row["id"] for row in plan["remove_images"]])
        self.assertEqual([image_id(37)], [row["id"] for row in plan["preserved_multitag_images"]])
        self.assertEqual([], self.docker.commands)

    def test_apply_only_removes_reviewed_image_without_force_or_container_volume_changes(self):
        result = self.run_maintenance(apply=True)
        self.assertEqual(1, result["removed_count"])
        self.assertEqual(("image", "rm", image_id(30)), self.docker.commands[0])
        self.assertEqual(("builder", "prune", "--force", "--filter", "until=24h", "--max-used-space", "4GB"),
                         self.docker.commands[1])
        self.assertTrue(all(args[:2] in [("image", "rm"), ("builder", "prune")] for args in self.docker.commands))
        state = mod.snapshot(self.docker, self.directory)
        self.assertTrue(all(record[key] in state["images"] for record in self.docker.history.values()
                            for key in ("current", "previous")))

    def test_containerd_local_build_digests_allow_old_image_cleanup_but_keep_releases(self):
        # Real Docker containerd builds populate RepoDigests even without a registry push.
        for row in self.docker.images:
            repository = row["RepoTags"][0].split(":", 1)[0]
            row["RepoDigests"] = [repository + "@" + row["Id"]]
        result = self.run_maintenance(apply=True, skip_cache=True)
        self.assertEqual(1, result["removed_count"])
        self.assertEqual([("image", "rm", image_id(30))], self.docker.commands)
        self.assertEqual(6, len(self.docker.images))

    def test_exact_legacy_local_tag_is_cleaned_but_current_rollback_recent_and_other_tags_are_kept(self):
        for row in self.docker.images:
            if row["Id"] == self.docker.history["identity"]["current"]:
                row["RepoTags"] = ["tradepass-identity:local"]
            if row["Id"] == self.docker.history["business"]["previous"]:
                row["RepoTags"] = ["tradepass-business:local"]
        self.docker.images.extend([image(31, tags=["tradepass-gateway:local"]),
                                   image(32, tags=["tradepass-business:development"])])
        result = self.run_maintenance(apply=True, skip_cache=True)
        self.assertEqual(2, result["removed_count"])
        self.assertEqual({image_id(30), image_id(31)}, {command[2] for command in self.docker.commands})
        remaining = {row["Id"] for row in self.docker.images}
        self.assertIn(self.docker.history["identity"]["current"], remaining)
        self.assertIn(self.docker.history["business"]["previous"], remaining)
        self.assertIn(image_id(32), remaining)
        recent = {"protected": set(), "images": {image_id(33): image(33, age=1, tags=["tradepass-gateway:local"])}}
        self.assertEqual([], mod.image_candidates(recent, 24))

    def test_external_registry_or_unrecognized_digests_preserve_local_tagged_image(self):
        old_image = self.docker.images[-1]
        for digest in ["docker.io/library/tradepass-business@" + image_id(30),
                       "registry.example/tradepass-business@" + image_id(30),
                       "tradepass-business@sha256:invalid"]:
            with self.subTest(digest=digest):
                old_image["RepoDigests"] = ["tradepass-business@" + image_id(30), digest]
                result = self.run_maintenance()
                self.assertEqual(0, result["remove_count"])
                self.assertEqual([], self.docker.commands)

    def test_rfc3339_nanoseconds_milliseconds_and_zones_parse_without_losing_age(self):
        now = datetime(2026, 11, 2, tzinfo=timezone.utc)
        for fraction, microseconds in [("093442387", 93442), ("09344238", 93442), ("093", 93000)]:
            for zone, hour in [("Z", "00"), ("+08:00", "08")]:
                with self.subTest(fraction=fraction, zone=zone):
                    value = "2026-11-01T" + hour + ":00:00." + fraction + zone
                    parsed = mod.parse_created(value)
                    self.assertEqual(datetime(2026, 11, 1, microsecond=microseconds, tzinfo=timezone.utc),
                                     parsed.astimezone(timezone.utc))
                    state = {"protected": set(), "images": {image_id(30): {**image(30), "Created": value}}}
                    # This is just newer than the 24-hour cutoff, regardless of zone/precision.
                    self.assertEqual([], mod.image_candidates(state, 24, now=now))
                    state["images"][image_id(30)]["Created"] = (
                        "2026-10-31T23:59:59." + fraction + "Z" if zone == "Z" else
                        "2026-11-01T07:59:59." + fraction + zone)
                    self.assertEqual([image_id(30)], [row["id"] for row in mod.image_candidates(state, 24, now=now)])

    def test_rfc3339_requires_a_valid_timezone_date_and_at_most_nine_fractional_digits(self):
        for value in ["2026-11-01T00:00:00", "2026-11-01T00:00:00.0934423870Z",
                      "2026-11-32T00:00:00Z", "2026-11-01T00:00:00+25:00", None]:
            with self.subTest(value=value), self.assertRaises(mod.SafetyError):
                mod.parse_created(value)

    def test_missing_history_or_rollback_or_unhealthy_service_aborts_all_mutations(self):
        cases = ["history", "rollback", "unhealthy", "wrong-current", "ownership", "transaction"]
        for case in cases:
            with self.subTest(case=case):
                self.docker = FakeDocker()
                self.write_history()
                journal = self.directory / "transaction.json"
                journal.unlink(missing_ok=True)
                if case == "history":
                    self.docker.history.pop("identity")
                    self.write_history()
                elif case == "rollback":
                    self.docker.images = [row for row in self.docker.images if row["Id"] != image_id(20)]
                elif case == "unhealthy":
                    self.docker.containers[0]["State"]["Health"]["Status"] = "unhealthy"
                elif case == "wrong-current":
                    self.docker.containers[0]["Image"] = image_id(30)
                elif case == "ownership":
                    self.docker.containers[0]["Config"]["Labels"]["com.docker.compose.project"] = "another-project"
                else:
                    journal.write_text("{}")
                with self.assertRaises(mod.SafetyError):
                    self.run_maintenance(apply=True)
                self.assertEqual([], self.docker.commands)

    def test_new_container_reference_during_cleanup_aborts_before_image_delete(self):
        calls = 0

        def idle():
            nonlocal calls
            calls += 1
            if calls == 2:
                self.docker.containers.append({"Name": "/new-work", "Image": image_id(30)})

        with self.assertRaisesRegex(mod.SafetyError, "protection"):
            mod.maintain(self.docker, self.directory, apply=True, idle_check=idle)
        self.assertEqual([], self.docker.commands)

    def test_history_change_between_plan_and_delete_aborts(self):
        calls = 0

        def idle():
            nonlocal calls
            calls += 1
            if calls == 2:
                self.docker.history["business"]["previous"] = image_id(30)
                self.write_history()

        with self.assertRaisesRegex(mod.SafetyError, "history changed"):
            mod.maintain(self.docker, self.directory, apply=True, idle_check=idle)
        self.assertEqual([], self.docker.commands)

    def test_build_starting_after_plan_aborts_without_deleting_images_or_cache(self):
        calls = 0

        def idle():
            nonlocal calls
            calls += 1
            if calls > 1:
                raise mod.BusyError("Build active")

        with self.assertRaises(mod.BusyError):
            mod.maintain(self.docker, self.directory, apply=True, idle_check=idle)
        self.assertEqual([], self.docker.commands)

    def test_retaging_image_outside_project_between_plan_and_delete_aborts(self):
        calls = 0

        def idle():
            nonlocal calls
            calls += 1
            if calls == 2:
                self.docker.images[-1]["RepoTags"].append("external-app:keep")

        with self.assertRaises(mod.SafetyError):
            mod.maintain(self.docker, self.directory, apply=True, idle_check=idle)
        self.assertEqual([], self.docker.commands)

    def test_failed_no_force_removal_does_not_continue_to_cache_pruning(self):
        with patch.object(self.docker, "run", side_effect=mod.SafetyError("Image in use")) as run:
            with self.assertRaises(mod.SafetyError):
                self.run_maintenance(apply=True)
            run.assert_called_once_with("image", "rm", image_id(30), timeout=180)

    def test_prune_cli_supports_current_and_legacy_docker_with_age_and_space_limits(self):
        docker = mod.Docker()
        for help_text, flag in [("--max-used-space bytes", "--max-used-space"),
                                ("--keep-storage string", "--keep-storage")]:
            with self.subTest(flag=flag), patch.object(docker, "run", return_value=help_text):
                self.assertEqual(["builder", "prune", "--force", "--filter", "until=48h", flag, "4GB"],
                                 docker.cache_command(48, "4GB"))
        with patch.object(docker, "run", return_value="no supported size limit"), self.assertRaises(mod.SafetyError):
            docker.cache_command(24, "4GB")

    def test_active_build_detection_uses_boundaries_and_excludes_idle_daemons(self):
        for argv in (["docker", "build", "."], ["docker", "image", "load", "-i", "x.tar"],
                     ["docker", "--context", "default", "build", "."], ["docker", "--host=unix:///x", "build", "."],
                     ["docker-buildx", "bake"], ["python3", "/tmp/core_release.py", "--service", "business"],
                     ["python3", "/tmp/core_apply.py", "deploy"], ["mvn", "package"],
                     ["java", "org.codehaus.plexus.classworlds.launcher.Launcher"]):
            with self.subTest(argv=argv):
                self.assertTrue(mod.is_build_command(argv))
        for argv in (["dockerd"], ["buildkitd"], ["docker", "image", "inspect", "sha256:x"],
                     ["docker", "builder", "prune", "--force"],
                     ["sh", "-c", "printf 'docker build'; printf core_apply.py"], ["java", "-jar", "app.jar"]):
            with self.subTest(argv=argv):
                self.assertFalse(mod.is_build_command(argv))

    def test_proc_scan_detects_build_and_fails_closed_when_proc_unavailable(self):
        proc = self.directory / "proc"
        (proc / "456").mkdir(parents=True)
        (proc / "456" / "cmdline").write_bytes(b"docker\0build\0.\0")
        with self.assertRaises(mod.BusyError):
            mod.assert_idle(proc)
        with self.assertRaises(mod.SafetyError):
            mod.assert_idle(self.directory / "missing-proc")

    def test_publish_lock_prevents_concurrent_maintenance(self):
        path = self.directory / "publish.lock"
        with mod.exclusive_lock(path), self.assertRaises(mod.BusyError):
            with mod.exclusive_lock(path):
                self.fail("Concurrent maintenance acquired the publisher lock")

    def test_remote_docker_endpoint_is_rejected(self):
        with patch.dict(mod.os.environ, {"DOCKER_HOST": "tcp://remote:2376"}), self.assertRaises(mod.SafetyError):
            mod.Docker().check_local()

    def test_selected_remote_context_cannot_be_hidden_by_local_docker_host(self):
        with patch.dict(mod.os.environ, {"DOCKER_HOST": "unix:///var/run/docker.sock", "DOCKER_CONTEXT": "remote"}), \
                patch.object(mod.Docker, "run", return_value="ssh://remote"), self.assertRaises(mod.SafetyError):
            mod.Docker().check_local()

    def test_cli_defaults_to_preview_and_busy_is_skipped(self):
        args = ["--history-dir", str(self.directory), "--lock-file", str(self.directory / "maintenance.lock")]
        with patch.object(mod, "Docker", return_value=self.docker), patch.object(mod, "maintain", return_value={"status": "dry-run"}) as maintain:
            self.assertEqual(0, mod.main(args))
            self.assertFalse(maintain.call_args.args[2])
        with patch.object(mod, "Docker", return_value=self.docker), patch.object(mod, "maintain", side_effect=mod.BusyError("Build active")):
            self.assertEqual(0, mod.main(args))
            self.assertEqual("skipped", json.loads(self.output.getvalue().splitlines()[-1])["status"])


if __name__ == "__main__":
    unittest.main()
