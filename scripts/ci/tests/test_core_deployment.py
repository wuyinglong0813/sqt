"""Check core topology and packaging without requiring private deployment credentials."""
from pathlib import Path
import importlib.util
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[3]


class CoreDeploymentTest(unittest.TestCase):
    def test_api_baseline_combines_class_paths_and_preserves_media_constraints(self):
        spec = importlib.util.spec_from_file_location("api_baseline", ROOT / "scripts/generate-http-api-baseline.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        source = r'''
            @RequestMapping("/api/contracts")
            class Controller {
                @GetMapping("/{id:\\d+}")
                @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
                @PostMapping(value = "/{id}/receive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
            }
        '''
        self.assertEqual([
            r"{GET [/api/contracts/{id:\d+}]}",
            "{GET [/api/contracts/{id}/pdf], produces [application/pdf]}",
            "{POST [/api/contracts/{id}/receive], consumes [multipart/form-data]}",
        ], module.collect_source(source))

    def test_core_preserves_data_volumes_and_only_required_infrastructure(self):
        old = yaml.safe_load((ROOT / "deploy/server/infra.compose.yml").read_text())
        core = yaml.safe_load((ROOT / "deploy/server/infra.core.compose.yml").read_text())
        self.assertEqual(old["name"], core["name"])
        self.assertEqual({"mysql", "redis", "nacos", "rocketmq-init", "rocketmq-namesrv",
                          "rocketmq-broker", "rocketmq-topic-init"}, set(core["services"]))
        for role in ("mysql", "redis", "rocketmq-broker"):
            before = [v for v in old["services"][role]["volumes"] if not v.startswith("./")]
            after = [v for v in core["services"][role]["volumes"] if not v.startswith("./")]
            self.assertEqual(before, after)
        self.assertEqual(["0.0.0.0:${MYSQL_PORT:-3306}:3306"], core["services"]["mysql"]["ports"])
        for role, service in core["services"].items():
            if role != "mysql":
                self.assertTrue(all(port.startswith("127.0.0.1:") for port in service.get("ports", [])))
        # The running Redis container uses the legacy overlay, not the core file.
        overlay = yaml.safe_load((ROOT / "deploy/server/infra.localhost.compose.yml").read_text())
        self.assertEqual(["0.0.0.0:${REDIS_PUBLISH_PORT:-6379}:6379"], overlay["services"]["redis"]["ports"])
        for role, service in overlay["services"].items():
            if role != "redis":
                self.assertTrue(all(port.startswith("127.0.0.1:") for port in service.get("ports", [])))
        self.assertIn("10909:10909", " ".join(core["services"]["rocketmq-broker"]["ports"]))

    def test_core_has_one_business_database_and_discovery_for_all_three_processes(self):
        core = yaml.safe_load((ROOT / "deploy/server/yudao.core.compose.yml").read_text())
        self.assertEqual({"identity", "business", "gateway"}, set(core["services"]))
        for service in core["services"].values():
            env = service["environment"]
            self.assertIn("core", env["SPRING_PROFILES_ACTIVE"].split(","))
            self.assertEqual("file:/app/nacos-bootstrap.yml", env["SPRING_CONFIG_ADDITIONAL_LOCATION"])
            self.assertEqual("false", env["TRADEPASS_TRACING_ENABLED"])
            self.assertFalse(any(k.startswith("TRADEPASS_") and k.endswith("_URL")
                                 and k != "TRADEPASS_DATABASE_URL" for k in env))
        env = core["services"]["business"]["environment"]
        self.assertNotIn("TRADEPASS_DATABASE_URL", env)
        self.assertNotIn("DB_PASSWORD", env)
        self.assertNotIn("FADADA_ENABLED", env)
        self.assertNotIn("WECHAT_APP_SECRET", env)
        self.assertIn("messaging", env["SPRING_PROFILES_ACTIVE"])
        self.assertFalse(any(k.startswith(("CONTRACT_DB", "TRADE_DB", "SETTLEMENT_DB")) for k in env))

    def test_resident_memory_caps_leave_room_for_os(self):
        total = 0
        for filename in ("infra.core.compose.yml", "yudao.core.compose.yml"):
            compose = yaml.safe_load((ROOT / "deploy/server" / filename).read_text())
            for name, service in compose["services"].items():
                if name in ("rocketmq-init", "rocketmq-topic-init"):
                    continue
                limit = service["mem_limit"]
                self.assertTrue(limit.endswith("m"))
                total += int(limit[:-1])
        self.assertLessEqual(total, 3584)


if __name__ == "__main__":
    unittest.main()
