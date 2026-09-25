"""Exercise lifecycle ordering and failure boundaries without touching live Docker."""
import contextlib
import importlib.util
import io
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("server_control", ROOT / "scripts/server/tradepass.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def healthy():
    return {"Status": "running", "Health": {"Status": "healthy"}, "ExitCode": 0}


class ServerControlTest(unittest.TestCase):
    def setUp(self):
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def test_business_start_waits_for_dependencies_before_each_start(self):
        selected = mod.select(["business"], dependencies=True)
        self.assertEqual(list(mod.SERVICES[:7]), selected)
        events = []
        with patch.object(mod, "state", return_value={"Status": "exited"}), \
                patch.object(mod, "docker", side_effect=lambda *a, **kw: (
                    events.append(("start", a[-1])), subprocess.CompletedProcess([], 0))[1]), \
                patch.object(mod, "wait_ready", side_effect=lambda s, t: events.append(("wait", s))):
            mod.start(selected, 360)
        self.assertEqual([event for s in selected for event in (
            ("start", mod.container(s)), ("wait", s))], events)

    def test_all_stop_is_reverse_order_with_grace_period(self):
        with patch.object(mod, "docker", return_value=subprocess.CompletedProcess([], 0)) as docker, \
                patch.object(mod, "state", return_value={"Status": "exited"}):
            mod.stop(list(mod.SERVICES), 90)
        self.assertEqual([
            (("stop", "--time", "90", mod.container(s)), {"timeout": 120})
            for s in reversed(mod.SERVICES)
        ], [(call.args, call.kwargs) for call in docker.call_args_list])

    def test_restart_business_only_stops_business_and_ensures_dependencies(self):
        with patch.object(mod, "docker", return_value=subprocess.CompletedProcess([], 0)), \
                patch.object(mod, "state", return_value=healthy()), \
                patch.object(mod, "stop") as stop, patch.object(mod, "start") as start:
            self.assertEqual(0, mod.main(["restart", "business"]))
        stop.assert_called_once_with(["business"], 90)
        start.assert_called_once_with(list(mod.SERVICES[:7]), 360)

    def test_missing_dependency_aborts_restart_before_stopping_anything(self):
        with patch.object(mod, "docker", return_value=subprocess.CompletedProcess([], 0)), \
                patch.object(mod, "state", side_effect=mod.OperationError("missing mysql")), \
                patch.object(mod, "stop") as stop, patch.object(mod, "start") as start:
            with self.assertRaises(mod.OperationError):
                mod.main(["restart", "business"])
        stop.assert_not_called()
        start.assert_not_called()

    def test_unhealthy_dependency_prevents_downstream_start(self):
        with patch.object(mod, "state", return_value=healthy()), \
                patch.object(mod, "wait_ready", side_effect=mod.OperationError("timeout")) as wait, \
                patch.object(mod, "docker") as docker:
            with self.assertRaises(mod.OperationError):
                mod.start(["mysql", "identity"], 1)
        wait.assert_called_once_with("mysql", 1)
        docker.assert_not_called()

    def test_wait_handles_starting_and_times_out_without_hanging(self):
        with patch.object(mod, "state", side_effect=[
            {"Status": "running", "Health": {"Status": "starting"}}, healthy(),
        ]), patch.object(mod.time, "sleep"):
            mod.wait_ready("identity", 360)
        with patch.object(mod, "state", return_value={"Status": "running"}), \
                patch.object(mod.time, "monotonic", side_effect=[0, 2]):
            with self.assertRaisesRegex(mod.OperationError, "超时"):
                mod.wait_ready("identity", 1)

    def test_stop_failure_does_not_stop_lower_dependencies(self):
        with patch.object(mod, "docker", return_value=subprocess.CompletedProcess([], 1)) as docker:
            with self.assertRaises(mod.OperationError):
                mod.stop(["mysql", "identity", "gateway"], 90)
        self.assertEqual(1, docker.call_count)
        self.assertEqual(mod.container("gateway"), docker.call_args.args[-1])

    def test_status_accepts_stopped_but_check_fails(self):
        with patch.object(mod, "state", return_value={"Status": "exited", "ExitCode": 137}):
            self.assertEqual(0, mod.status(["identity"], check=False))
            self.assertEqual(1, mod.status(["identity"], check=True))
        self.assertTrue(mod.ready("nginx", {"Status": "running"}))
        self.assertFalse(mod.ready("identity", {"Status": "running"}))
        self.assertFalse(mod.ready("nginx", {"Status": "running", "Health": {"Status": "unhealthy"}}))

    def test_missing_service_is_reported_without_hiding_other_statuses(self):
        with patch.object(mod, "state", side_effect=[mod.OperationError("missing"), healthy()]) as state:
            self.assertEqual(1, mod.status(["mysql", "identity"], check=True))
        self.assertEqual(2, state.call_count)

    def test_dry_run_and_invalid_targets_never_touch_docker(self):
        with patch.object(mod, "docker") as docker:
            self.assertEqual(0, mod.main(["restart", "all", "--dry-run"]))
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                mod.main(["stop", "not-a-service"])
        docker.assert_not_called()

    def test_existing_healthy_services_are_not_restarted(self):
        with patch.object(mod, "state", return_value=healthy()), patch.object(mod, "docker") as docker:
            mod.start(["identity", "business"], 360)
        docker.assert_not_called()

    def test_init_jobs_are_not_included_and_targets_are_deduplicated(self):
        self.assertEqual(list(mod.SERVICES), mod.select(["all", "apps", "mysql"], dependencies=True))
        self.assertNotIn("rocketmq-init", mod.SERVICES)
        self.assertNotIn("rocketmq-topic-init", mod.SERVICES)


if __name__ == "__main__":
    unittest.main()
