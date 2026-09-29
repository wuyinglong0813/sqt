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
                "config": {"import": ["nacos:" + name + ".yaml?group=TRADEPASS_CORE&refreshEnabled=true"
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


class ExistingContainerConfigurationTest(unittest.TestCase):
    def fixture(self, **overrides):
        # This matches the server's reported layout: host network, only /root/logs mounted.
        environment = {
            "SPRING_PROFILES_ACTIVE": "observability,filelog,nacos,core,messaging",
            "NACOS_SERVER_ADDR": "127.0.0.1:8848", "NACOS_GROUP": "TRADEPASS_CORE",
            "NACOS_NAMESPACE": "", "NACOS_USERNAME": "nacos", "NACOS_PASSWORD": "private$nacos${literal}",
            "ROCKETMQ_NAME_SERVER": "127.0.0.1:9876", "ROCKETMQ_CALLBACK_TOPIC": "tradepass-callback-events",
            "ROCKETMQ_ACCESS_KEY": "", "ROCKETMQ_SECRET_KEY": "",
            "SPRING_CLOUD_NACOS_DISCOVERY_IP": "127.0.0.1", "JAVA_OPTS": "-Xms64m -Xmx256m",
        }
        environment.update(overrides)
        return {"HostConfig": {"NetworkMode": "host"},
                "Config": {"Env": [key + "=" + value for key, value in environment.items()],
                           "Entrypoint": ["sh", "/app/entrypoint.sh"], "Cmd": None},
                "Mounts": [{"Type": "bind", "Destination": "/root/logs", "Source": "/docker/tradepass/logs"}]}

    def test_reported_server_layout_reads_nacos_and_uses_container_mq_names(self):
        with patch.object(mod, "run", return_value=json.dumps([self.fixture()])) as run, \
                patch.object(mod, "read_nacos", return_value=({}, {"tradepass": {"configuration-version": 1}})) as read:
            plan = mod.configured_plan()
        self.assertEqual("tradepass-callback-events", plan["resources"][0]["topic"])
        self.assertEqual("tradepass-contract-callback-consumer", plan["resources"][0]["consumerGroup"])
        self.assertEqual("TRADEPASS_CORE", read.call_args.args[0]["group"])
        self.assertEqual("private$nacos${literal}", read.call_args.args[0]["password"])
        run.assert_called_once_with("docker", "inspect", "tradepass-core-business-1")

    def test_legacy_custom_topic_group_and_acl_are_not_replaced_with_defaults(self):
        fixture = self.fixture(ROCKETMQ_CALLBACK_TOPIC="custom-events", ROCKETMQ_CONSUMER_GROUP="custom-consumers",
                               ROCKETMQ_ACCESS_KEY="mq-key", ROCKETMQ_SECRET_KEY="private-mq-secret")
        with patch.object(mod, "run", return_value=json.dumps([fixture])), \
                patch.object(mod, "read_nacos", return_value=({}, {})):
            plan = mod.configured_plan()
        self.assertEqual("custom-events", plan["resources"][0]["topic"])
        self.assertEqual("custom-consumers", plan["resources"][0]["consumerGroup"])
        self.assertEqual("private-mq-secret", plan["secretKey"])

    def test_direct_spring_properties_override_remote_and_placeholder_aliases(self):
        fixture = self.fixture(TRADEPASS_MESSAGING_ROCKETMQ_CALLBACK_TOPIC="direct-events")
        with patch.object(mod, "run", return_value=json.dumps([fixture])), \
                patch.object(mod, "read_nacos", return_value=({}, {mod.PREFIX + "callback-topic": "nacos-events"})):
            plan = mod.configured_plan()
        self.assertEqual("direct-events", plan["resources"][0]["topic"])

    def test_legacy_conflicting_nacos_and_profile_values_are_rejected_without_values(self):
        with patch.object(mod, "run", return_value=json.dumps([self.fixture()])), \
                patch.object(mod, "read_nacos", return_value=({}, {mod.PREFIX + "callback-topic": "other-remote-events"})):
            with self.assertRaisesRegex(ValueError, "配置冲突") as failure:
                mod.configured_plan()
        self.assertNotIn("other-remote-events", str(failure.exception))

    def test_legacy_agreeing_remote_values_and_placeholders_are_supported(self):
        remote = {mod.PREFIX + "enabled": True, mod.PREFIX + "name-server": "${ROCKETMQ_NAME_SERVER}",
                  mod.PREFIX + "callback-topic": "${ROCKETMQ_CALLBACK_TOPIC:default-topic}"}
        with patch.object(mod, "run", return_value=json.dumps([self.fixture()])), \
                patch.object(mod, "read_nacos", return_value=({}, remote)):
            self.assertEqual("tradepass-callback-events", mod.configured_plan()["resources"][0]["topic"])

    def test_native_bootstrap_still_uses_remote_properties(self):
        fixture = self.fixture(SPRING_PROFILES_ACTIVE="observability,filelog,core,messaging",
                               SPRING_CONFIG_ADDITIONAL_LOCATION="file:/app/nacos-bootstrap.yml")
        fixture["Mounts"].append({"Type": "bind", "Destination": "/app/nacos-bootstrap.yml", "Source": "/private/bootstrap.yml"})
        options = {"enabled": True, "server-addr": "127.0.0.1:8848", "group": "TRADEPASS_CORE"}
        with patch.object(mod, "run", return_value=json.dumps([fixture])), \
                patch.object(mod, "nacos_options", return_value=options) as connection, \
                patch.object(mod, "read_nacos", return_value=({}, {mod.PREFIX + "callback-topic": "native-events"})):
            self.assertEqual("native-events", mod.configured_plan()["resources"][0]["topic"])
        self.assertEqual(Path("/private/bootstrap.yml"), connection.call_args.args[0])

    def test_no_nacos_profile_does_not_read_nacos_or_operator_shell(self):
        fixture = self.fixture(SPRING_PROFILES_ACTIVE="observability,filelog,core,messaging")
        with patch.object(mod, "run", return_value=json.dumps([fixture])), patch.object(mod, "read_nacos") as read, \
                patch.dict("os.environ", {"ROCKETMQ_CALLBACK_TOPIC": "wrong-shell-topic"}):
            self.assertEqual("tradepass-callback-events", mod.configured_plan()["resources"][0]["topic"])
        read.assert_not_called()

    def test_config_overrides_missing_mount_and_unconfirmed_profiles_stop_before_reading(self):
        for overrides in ({"SPRING_APPLICATION_JSON": "private-json"}, {"JAVA_OPTS": "-Dtradepass.messaging.rocketmq.callback-topic=private"},
                          {"SPRING_CONFIG_IMPORT": "file:/custom.yml"}, {"SPRING_PROFILES_ACTIVE": "unknown"},
                          {"SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/app/nacos-bootstrap.yml"}):
            with self.subTest(overrides=list(overrides)), \
                    patch.object(mod, "run", return_value=json.dumps([self.fixture(**overrides)])), \
                    patch.object(mod, "read_nacos") as read:
                with self.assertRaises(ValueError) as failure:
                    mod.configured_plan()
                self.assertNotIn("private", str(failure.exception))
                read.assert_not_called()

    def test_nacos_group_and_server_overrides_cannot_silently_point_elsewhere(self):
        for overrides in ({"NACOS_SERVER_ADDR": "remote:8848"},
                          {"SPRING_CLOUD_NACOS_CONFIG_GROUP": "OTHER_GROUP"},
                          {"SPRING_CLOUD_NACOS_CONFIG_ENABLED": "false"}):
            with self.subTest(overrides=list(overrides)), \
                    patch.object(mod, "run", return_value=json.dumps([self.fixture(**overrides)])), \
                    patch.object(mod, "read_nacos") as read:
                with self.assertRaises(ValueError):
                    mod.configured_plan()
                read.assert_not_called()

    def test_legacy_nacos_read_failure_never_falls_back_to_container_defaults(self):
        with patch.object(mod, "run", return_value=json.dumps([self.fixture()])), \
                patch.object(mod, "read_nacos", side_effect=ValueError("读取 Nacos 失败")):
            with self.assertRaisesRegex(ValueError, "读取 Nacos 失败"):
                mod.configured_plan()


if __name__ == "__main__":
    unittest.main()
