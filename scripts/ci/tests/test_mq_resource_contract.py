"""Resource contract coverage, configuration resolution, and fail-closed deployment boundaries."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("mq_resources", ROOT / "scripts/server/mq_resources.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
CATALOG = json.loads((mod.ASSETS / "resources.json").read_text())


class ResourceContractTest(unittest.TestCase):
    def test_all_production_mq_adapters_are_registered(self):
        # A new producer/consumer integration must extend the deployment inventory.
        pattern = re.compile(r"new\s+(?:[\w.]+\.)?(?:DefaultMQProducer|DefaultMQPushConsumer|DefaultLitePullConsumer|DefaultMQPullConsumer|TransactionMQProducer)\s*\(|RocketMQTemplate|@RocketMQMessageListener")
        adapters = {str(path.relative_to(ROOT)) for path in ROOT.glob("**/src/main/java/**/*.java")
                    if "/target/" not in str(path) and pattern.search(path.read_text())}
        self.assertEqual({resource["adapter"] for resource in CATALOG["resources"]}, adapters)
        for resource in CATALOG["resources"]:
            source = (ROOT / resource["adapter"]).read_text()
            for key in ("topic", "consumerGroup"):
                self.assertIn("${" + resource[key + "Property"] + ":" + resource[key] + "}", source)

    def test_nacos_overrides_resolve_every_resource_and_do_not_mutate_catalog(self):
        catalog = copy.deepcopy(CATALOG)
        catalog["resources"].append({**catalog["resources"][0], "id": "future-feature",
                                     "topicProperty": "future.topic", "consumerGroupProperty": "future.group"})
        plan = mod.resolve_plan(catalog, {mod.PREFIX + "name-server": "127.0.0.1:9876", mod.PREFIX + "callback-topic": "common-topic"}, {
            mod.PREFIX + "callback-topic": "business-topic", mod.PREFIX + "consumer-group": "business-group",
            "future.topic": "future-events", "future.group": "future-consumers"})
        self.assertEqual(["business-topic", "future-events"], [r["topic"] for r in plan["resources"]])
        self.assertEqual(["business-group", "future-consumers"], [r["consumerGroup"] for r in plan["resources"]])
        self.assertEqual("tradepass-callback-events", catalog["resources"][0]["topic"])

    def test_bootstrap_reads_nacos_values_and_never_falls_back_on_read_failure(self):
        import yaml
        client = Mock()
        client.read.side_effect = ["{}", yaml.safe_dump({mod.PREFIX + "name-server": "127.0.0.1:9876",
                                                      mod.PREFIX + "callback-topic": "configured-events"})]
        reader = Mock(Nacos=Mock(return_value=client))
        boot = {"spring": {"cloud": {"nacos": {"config": {"enabled": True, "server-addr": "127.0.0.1:8848",
                "group": "TRADEPASS_CORE", "username": "test", "password": "private-password"}}},
                "config": {"import": ["nacos:" + name + ".yaml?group=TRADEPASS_CORE&refreshEnabled=false"
                                      for name in ("tradepass-common", "${spring.application.name}")]}}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bootstrap.yml"
            path.write_text(yaml.safe_dump(boot))
            with patch.object(mod.importlib.util, "spec_from_file_location", return_value=Mock()), \
                    patch.object(mod.importlib.util, "module_from_spec", return_value=reader):
                plan = mod.configured_plan(path)
                self.assertEqual("configured-events", plan["resources"][0]["topic"])
                self.assertEqual([(("common",), {}), (("business",), {})],
                                 [(call.args, call.kwargs) for call in client.read.call_args_list])
                client.read.side_effect = RuntimeError("private-password")
                with self.assertRaises(ValueError) as failure:
                    mod.configured_plan(path)
                self.assertNotIn("private-password", str(failure.exception))

    def test_mixed_dotted_yaml_keys_are_resolved_and_duplicate_forms_rejected(self):
        doc = {"tradepass": {"messaging.rocketmq": {"name-server": "127.0.0.1:9876", "callback-topic": "mixed-topic"}}}
        self.assertEqual("mixed-topic", mod.resolve_plan(CATALOG, {}, doc)["resources"][0]["topic"])
        doc[mod.PREFIX + "callback-topic"] = "conflicting-topic"
        with self.assertRaises(ValueError):
            mod.resolve_plan(CATALOG, {}, doc)

    def test_missing_required_nameserver_is_not_replaced_with_a_default(self):
        with self.assertRaises(ValueError):
            mod.resolve_plan(CATALOG, {}, {})

    def test_unresolved_configuration_or_wrong_cluster_is_rejected(self):
        for key, value in (("callback-topic", "${UNKNOWN}"), ("consumer-group", ""),
                           ("name-server", "elsewhere:9876"), ("enabled", False),
                           ("access-key", "only-half"), ("secret-key", "${SECRET}")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                mod.resolve_plan(CATALOG, {mod.PREFIX + "name-server": "127.0.0.1:9876"}, {mod.PREFIX + key: value})

    def test_zero_exit_without_verified_success_is_rejected_and_temp_is_removed(self):
        for stdout, code in (("java exception\n", 0), ("MQ_RESOURCES_NOT_READY\n", 2)):
            with patch.object(mod, "run", return_value="/tmp/tradepass-mq-resources.abcdef") as run, \
                    patch.object(mod.subprocess, "run", return_value=Mock(stdout=stdout, returncode=code)):
                with self.assertRaises(ValueError):
                    mod.execute("check", CATALOG, "test-broker")
                self.assertEqual(("docker", "exec", "test-broker", "rm", "-rf", "--", "/tmp/tradepass-mq-resources.abcdef"), run.call_args.args)

    def test_credentials_travel_only_on_stdin_and_library_logs_are_not_emitted(self):
        import contextlib
        import io
        output = io.StringIO()
        with patch.object(mod, "run", return_value="/tmp/tradepass-mq-resources.abcdef"), \
                patch.object(mod.subprocess, "run", return_value=Mock(stdout="secret-library-log\nMQ_RESOURCES_OK\n", returncode=0)) as run, \
                contextlib.redirect_stdout(output):
            mod.execute("check", {**CATALOG, "accessKey": "key", "secretKey": "private-secret"}, "test-broker")
        self.assertNotIn("private-secret", str(run.call_args.args))
        self.assertIn("private-secret", run.call_args.kwargs["input"])
        self.assertEqual("MQ_RESOURCES_OK\n", output.getvalue())

    def test_init_templates_use_direct_broker_and_shared_manifest(self):
        import yaml
        for name in ("infra.compose.yml", "infra.core.compose.yml"):
            init = yaml.safe_load((ROOT / "deploy/server" / name).read_text())["services"]["rocketmq-topic-init"]
            self.assertEqual("rocketmq-broker:10911", init["command"][-1])
            self.assertIn("/opt/tradepass-mq/resources.json", init["command"])
            self.assertNotIn("mqadmin", str(init["command"]))


if __name__ == "__main__":
    unittest.main()
