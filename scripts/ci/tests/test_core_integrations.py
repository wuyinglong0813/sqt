import argparse
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/server/configure-core-integrations.py"
SPEC = importlib.util.spec_from_file_location("core_integrations", SCRIPT)
config = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(config)


def settings():
    return {
        "WECHAT_APP_ID": "wx0123456789abcdef",
        "WECHAT_APP_SECRET": "a" * 32,
        "FADADA_ENABLED": "true",
        "FADADA_APP_ID": "test-app",
        "FADADA_APP_SECRET": "test-secret$with#literal`characters",
        "FADADA_SERVER_URL": "https://uat.example.test/api/v5",
        "FADADA_CALLBACK_URL": "https://service.example.test/api/fadada/callback",
    }


def arguments(**overrides):
    values = dict(wechat_app_id=None, fadada_app_id=None, fadada_server_url=None, callback_url=None)
    values.update(overrides)
    return argparse.Namespace(**values)


class CoreIntegrationsTest(unittest.TestCase):
    def test_disabled_template_cannot_replace_the_old_fadada_environment(self):
        current = {"WECHAT_APP_ID": "", "WECHAT_APP_SECRET": "", "FADADA_ENABLED": "false",
                   "FADADA_SERVER_URL": "https://production.example.test/api/v5"}
        with patch.object(config, "prompt_value", side_effect=AssertionError("unexpected prompt")):
            self.assertEqual(settings(), config.choose_values(current, settings(), arguments()))

    def test_changed_app_requires_its_own_secret(self):
        with patch.object(config, "prompt_value", return_value="b" * 32) as prompt:
            updated = config.choose_values({}, settings(), arguments(wechat_app_id="wx1111111111111111"))
        self.assertEqual("b" * 32, updated["WECHAT_APP_SECRET"])
        prompt.assert_called_once_with("WECHAT_APP_SECRET", secret=True)

    def test_two_empty_templates_require_the_real_fadada_url(self):
        previous = settings()
        previous.update(FADADA_ENABLED="false", FADADA_APP_ID="", FADADA_APP_SECRET="",
                        FADADA_SERVER_URL="https://production.example.test/api/v5")
        current = {"FADADA_ENABLED": "false", "FADADA_SERVER_URL": previous["FADADA_SERVER_URL"]}
        with patch.object(config, "prompt_value", side_effect=lambda key, **unused: settings()[key]) as prompt:
            updated = config.choose_values(current, previous, arguments())
        self.assertEqual(settings(), updated)
        self.assertEqual(["FADADA_APP_ID", "FADADA_SERVER_URL", "FADADA_APP_SECRET"],
                         [call.args[0] for call in prompt.call_args_list])

    def test_changed_fadada_app_does_not_reuse_another_apps_url(self):
        answers = {"FADADA_SERVER_URL": "https://another.example.test/api/v5",
                   "FADADA_APP_SECRET": "new-app-secret"}
        with patch.object(config, "prompt_value", side_effect=lambda key, **unused: answers[key]):
            updated = config.choose_values({}, settings(), arguments(fadada_app_id="new-app"))
        self.assertEqual(answers["FADADA_SERVER_URL"], updated["FADADA_SERVER_URL"])
        self.assertEqual(answers["FADADA_APP_SECRET"], updated["FADADA_APP_SECRET"])

    def test_changed_fadada_environment_does_not_reuse_old_secret(self):
        with patch.object(config, "prompt_value", return_value="new-environment-secret") as prompt:
            updated = config.choose_values({}, settings(), arguments(fadada_server_url="https://production.example.test/api/v5"))
        self.assertEqual("new-environment-secret", updated["FADADA_APP_SECRET"])
        prompt.assert_called_once_with("FADADA_APP_SECRET", secret=True)

    def test_renderer_keeps_database_middleware_storage_and_tls_bytes(self):
        untouched = ("# existing private deployment\r\nMYSQL_ROOT_PASSWORD='root$#literal'\r\n"
                     "IDENTITY_DATABASE_URL='jdbc:mysql://127.0.0.1/db?a=1&b=2'\n"
                     "BUSINESS_DB_PASSWORD=keep-this\nNACOS_PASSWORD=keep-nacos\n"
                     "TRADEPASS_STORAGE_ENABLED=false\nTRADEPASS_TLS_DIRECTORY=/docker/tradepass/tls\n")
        original = untouched + "WECHAT_APP_ID=\nWECHAT_APP_SECRET=\nWECHAT_APP_ID=duplicate\n"
        rendered = config.render(original, settings())
        self.assertTrue(rendered.startswith(untouched))
        self.assertEqual(1, rendered.count("WECHAT_APP_ID="))
        self.assertEqual(settings(), config.read_settings(rendered))
        self.assertEqual(rendered, config.render(rendered, settings()))

    def test_read_only_whitelist_and_never_evaluate_shell_expressions(self):
        content = ('WECHAT_APP_ID="wx0123456789abcdef" # app\n'
                   'WECHAT_APP_SECRET=${SECRET_FROM_SHELL}\n'
                   "FADADA_APP_SECRET='literal$#secret'\n"
                   'MYSQL_ROOT_PASSWORD=not-an-integration\n'
                   'FADADA_APP_ID=$(touch /tmp/must-not-run)\n')
        values = config.read_settings(content)
        self.assertEqual("", values["WECHAT_APP_SECRET"])
        self.assertEqual("", values["FADADA_APP_ID"])
        self.assertEqual("literal$#secret", values["FADADA_APP_SECRET"])
        self.assertNotIn("MYSQL_ROOT_PASSWORD", values)

    def test_multiline_and_incomplete_credentials_are_rejected_without_echoing_them(self):
        for key, secret in (("WECHAT_APP_SECRET", "cut-off-secret"),
                            ("FADADA_APP_SECRET", "private\ninjected=true"),
                            ("FADADA_APP_SECRET", "private'quote"),
                            ("FADADA_APP_SECRET", "private\\escape")):
            values = settings()
            values[key] = secret
            with self.assertRaises(ValueError) as caught:
                config.render("MYSQL_ROOT_PASSWORD=keep\n", values)
            self.assertNotIn(secret, str(caught.exception))

    def test_cli_import_backup_permissions_and_idempotence(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / ".env.core"
            source = Path(directory) / ".env"
            original = b"MYSQL_ROOT_PASSWORD=existing\nFADADA_ENABLED=false\n"
            target.write_bytes(original)
            target.chmod(0o600)
            source.write_text(config.render("", settings()))
            command = [sys.executable, str(SCRIPT), "--env-file", str(target)]
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(0o600, target.stat().st_mode & 0o777)
            backups = list(Path(directory).glob(".env.core.backup-*"))
            self.assertEqual(1, len(backups))
            self.assertEqual(original, backups[0].read_bytes())
            self.assertEqual(0o600, backups[0].stat().st_mode & 0o777)
            self.assertTrue(target.read_text().startswith("MYSQL_ROOT_PASSWORD=existing\n"))
            self.assertEqual(settings(), config.read_settings(target.read_text()))
            for key in ("WECHAT_APP_SECRET", "FADADA_APP_SECRET"):
                self.assertNotIn(settings()[key], result.stdout + result.stderr)
            again = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertEqual(0, again.returncode, again.stderr)
            self.assertEqual(backups, list(Path(directory).glob(".env.core.backup-*")))

    def test_missing_secret_noninteractive_does_not_change_any_files(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / ".env.core"
            original = b"MYSQL_ROOT_PASSWORD=existing\nWECHAT_APP_SECRET=\n"
            target.write_bytes(original)
            target.chmod(0o600)
            result = subprocess.run([sys.executable, str(SCRIPT), "--env-file", str(target),
                                     "--fadada-app-id", "test-app", "--fadada-server-url", "https://uat.example.test/api/v5"],
                                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("WECHAT_APP_SECRET", result.stderr)
            self.assertEqual(original, target.read_bytes())
            self.assertEqual([], list(Path(directory).glob(".env.core.backup-*")))

    def test_concurrent_file_change_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / ".env.core"
            target.write_bytes(b"operator changed this")
            with self.assertRaises(ValueError):
                config.write_private(target, b"old content", b"replacement")
            self.assertEqual(b"operator changed this", target.read_bytes())

    def test_failed_replace_keeps_original_and_private_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / ".env.core"
            target.write_bytes(b"original")
            with patch.object(config.os, "replace", side_effect=OSError("test failure")):
                with self.assertRaises(OSError):
                    config.write_private(target, b"original", b"updated")
            self.assertEqual(b"original", target.read_bytes())
            self.assertEqual([], list(Path(directory).glob(".env.core.update-*")))
            self.assertEqual(b"original", next(Path(directory).glob(".env.core.backup-*")).read_bytes())


if __name__ == "__main__":
    unittest.main()
