"""Verify native configuration mapping and publishing without private credentials."""
import copy
import hashlib
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import yaml

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("core_nacos", ROOT / "scripts/server/configure-core-nacos.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def settings():
    values = {key: default for key, _, default in mod.COMMON + mod.BUSINESS + mod.DATABASE + mod.BOOTSTRAP}
    values.update(TRADEPASS_INTERNAL_KEY="test-internal-" + "x" * 32, NACOS_PASSWORD="test-nacos-password",
                  WECHAT_APP_SECRET="wx-secret-$#not-expanded", FADADA_APP_SECRET="fdd-secret")
    for role in ("identity", "business"):
        values[role.upper() + "_DATABASE_URL"] = "jdbc:mysql://127.0.0.1:3306/test_" + role + "?useSSL=false"
        values[role.upper() + "_DB_USERNAME"] = role
        values[role.upper() + "_DB_PASSWORD"] = role + "-secret"
    return values


class CoreNacosTest(unittest.TestCase):
    def test_compose_dollar_formats_are_normalized_and_shell_values_ignored(self):
        for dollar, password in (("$$", "test$$literal"), ("$", "test$literal")):
            result = {"services": {"tradepass-config-dollar-probe": {"environment": {"DOLLAR": dollar}},
                                   "read": {"environment": {"PASSWORD": password}}}}
            with patch.object(mod.subprocess, "run", return_value=Mock(returncode=0, stdout=json.dumps(result))) as run:
                with patch.dict(mod.os.environ, {"WECHAT_APP_SECRET": "stale", "COMPOSE_PROJECT_NAME": "wrong"}):
                    config = mod.compose_config(Path("/tmp/fake.env"), document={"services": {}})
                self.assertEqual("test$literal", config["services"]["read"]["environment"]["PASSWORD"])
                self.assertNotIn("WECHAT_APP_SECRET", run.call_args.kwargs["env"])
                self.assertNotIn("COMPOSE_PROJECT_NAME", run.call_args.kwargs["env"])

    def test_no_files_or_backups_on_partial_publish_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            server = Path(directory)
            client = Mock()
            client.read.return_value = "tradepass: {configuration-version: 1}\n"
            client.publish.side_effect = [None, ValueError("Nacos 未确认配置发布")]
            with patch.object(mod, "SERVER", server), patch.object(mod, "read_source", return_value=settings()), \
                    patch.object(mod, "Nacos", return_value=client), patch.object(mod, "compose_config", return_value={}), \
                    patch("sys.argv", ["configure-core-nacos.py", "--publish"]), patch("builtins.print"):
                with self.assertRaises(ValueError):
                    mod.main()
            self.assertEqual([], list(server.iterdir()))

    def test_success_writes_only_bootstrap_and_startup_files(self):
        with tempfile.TemporaryDirectory() as directory:
            server = Path(directory)
            client = Mock()
            client.read.return_value = "tradepass: {configuration-version: 1}\n"
            with patch.object(mod, "SERVER", server), patch.object(mod, "read_source", return_value=settings()), \
                    patch.object(mod, "Nacos", return_value=client), patch.object(mod, "compose_config", return_value={}), \
                    patch("sys.argv", ["configure-core-nacos.py", "--publish"]), patch("builtins.print"):
                mod.main()
            files = sorted(str(path.relative_to(server)) for path in server.rglob("*") if path.is_file())
            self.assertEqual([".runtime/core.compose.yml", ".runtime/edge.compose.yml", ".runtime/infra.compose.yml", ".runtime/nacos/bootstrap.yml"], files)
            self.assertEqual(4, client.publish.call_count)
            boot = (server / ".runtime/nacos/bootstrap.yml").read_text()
            self.assertNotIn("wx-secret", boot)
            self.assertNotIn("business-secret", boot)

    def test_scope_databases_and_storage_to_their_services(self):
        docs = mod.documents(settings())
        mod.validate_documents(docs)
        self.assertEqual("identity-secret", mod.get_path(docs["identity"], "spring.datasource.password"))
        self.assertEqual("business-secret", mod.get_path(docs["business"], "spring.datasource.password"))
        self.assertNotIn("spring", docs["gateway"])
        self.assertIsNone(mod.get_path(docs["common"], "spring.datasource"))
        self.assertIsNone(mod.get_path(docs["identity"], "tradepass.storage"))
        self.assertEqual("wx-secret-$#not-expanded", mod.get_path(docs["common"], "wechat.app-secret"))
        self.assertEqual("lb://tradepass-business", mod.get_path(docs["gateway"], "tradepass.services.contract-url"))
        self.assertEqual("", mod.get_path(docs["identity"], "tradepass.services.contract-url"))
        self.assertEqual("127.0.0.1:9876", mod.get_path(docs["business"], "tradepass.messaging.rocketmq.name-server"))

    def test_existing_nacos_values_win_without_mutating_original(self):
        original = {"tradepass": {"fadada": {"enabled": True, "app-secret": "latest-secret"}}, "custom": {"timeout": 42}}
        existing = copy.deepcopy(original)
        merged = mod.merge_missing(existing, mod.documents(settings())["common"])
        self.assertEqual(original, existing)
        self.assertEqual("latest-secret", mod.get_path(merged, "tradepass.fadada.app-secret"))
        self.assertTrue(mod.get_path(merged, "tradepass.fadada.enabled"))
        self.assertEqual(42, mod.get_path(merged, "custom.timeout"))
        self.assertEqual(2, mod.get_path(merged, "tradepass.ids.datacenter-id"))

    def test_bootstrap_has_only_connection_data_and_required_remote_imports(self):
        boot = mod.bootstrap(settings())
        text = yaml.safe_dump(boot)
        self.assertNotIn("test-internal", text)
        self.assertNotIn("wx-secret", text)
        self.assertNotIn("datasource", text)
        imports = boot["spring"]["config"]["import"]
        self.assertEqual(2, len(imports))
        self.assertTrue(all(i.startswith("nacos:") and "refreshEnabled=false" in i for i in imports))
        self.assertTrue(boot["spring"]["cloud"]["nacos"]["config"]["enabled"])

    def test_invalid_or_remote_bootstrap_is_rejected(self):
        for key, value in (("NACOS_PASSWORD", "nacos"), ("NACOS_SERVER_ADDR", "evil.example:8848"), ("NACOS_GROUP", "a&tenant=other")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                mod.bootstrap({**settings(), key: value})

    def test_refuses_wrong_database_and_missing_credentials(self):
        for role in ("identity", "business"):
            docs = mod.documents(settings())
            mod.set_path(docs[role], "spring.datasource.url", "jdbc:mysql://127.0.0.1/legacy")
            with self.assertRaises(ValueError):
                mod.validate_documents(docs)
        docs = mod.documents(settings())
        mod.set_path(docs["business"], "spring.datasource.password", "")
        with self.assertRaises(ValueError):
            mod.validate_documents(docs)

    def test_cas_header_and_readback_are_required(self):
        client = object.__new__(mod.Nacos)
        client.params = {"group": "TRADEPASS_CORE", "tenant": "", "accessToken": "test-token"}
        previous, updated = "old: value\n", "new: value\n"
        client.read = Mock(side_effect=[previous, updated])
        client.request = Mock(return_value="true")
        client.publish("identity", updated, previous)
        args, kwargs = client.request.call_args
        self.assertEqual(hashlib.md5(previous.encode()).hexdigest(), kwargs["headers"]["casMd5"])
        self.assertNotIn("casMd5", args[1])
        client.read = Mock(return_value="concurrent change")
        client.request.reset_mock()
        with self.assertRaises(ValueError):
            client.publish("identity", updated, previous)
        client.request.assert_not_called()

    def test_private_files_and_compose_literal_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bootstrap.yml"
            mod.private_write(path, "password: test-secret\n")
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual([path], list(Path(directory).iterdir()))
            linked = Path(directory) / "link"
            linked.symlink_to(path)
            with self.assertRaises(ValueError):
                mod.private_write(linked, "bad")
        self.assertEqual({"environment": {"PASSWORD": "a$$b"}}, mod.compose_literal({"environment": {"PASSWORD": "a$b"}}))

    def test_application_compose_has_no_business_env_or_old_env_file(self):
        compose = yaml.safe_load((ROOT / "deploy/server/yudao.core.compose.yml").read_text())
        banned = {key for key, _, _ in mod.COMMON + mod.BUSINESS + mod.DATABASE + mod.BOOTSTRAP}
        for service in compose["services"].values():
            self.assertFalse(banned.intersection(service["environment"]))
            self.assertNotIn("env_file", service)
            volume = next(v for v in service["volumes"] if v["target"] == "/app/nacos-bootstrap.yml")
            self.assertFalse(volume["bind"]["create_host_path"])


if __name__ == "__main__":
    unittest.main()
