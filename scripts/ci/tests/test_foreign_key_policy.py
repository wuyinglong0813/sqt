"""Keep removal policy consistent across every deployable schema layout."""
import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class ForeignKeyPolicyTest(unittest.TestCase):
    def test_all_generated_migrations_and_manual_script_are_current(self):
        spec = importlib.util.spec_from_file_location('no_fk', ROOT / 'scripts/generate-no-foreign-keys.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        outputs = module.outputs()
        self.assertEqual(7, len(outputs))
        for path, expected in outputs.items():
            self.assertEqual(expected, path.read_text(), str(path))

    def test_new_migrations_do_not_introduce_constraints_or_disable_checks(self):
        paths = list((ROOT / 'sql/mysql').glob('V*.sql'))
        paths += list(ROOT.glob('tradepass-*/**/src/main/resources/db/**/V*.sql'))
        for path in paths:
            version = int(re.match(r'V(\d+)__', path.name)[1])
            frozen = version <= 36 if path.parent == ROOT / 'sql/mysql' else version == 1
            if frozen:
                continue
            sql = path.read_text().upper()
            self.assertNotRegex(sql, r'FOREIGN\s+KEY\s*\(', str(path))
            self.assertNotIn('FOREIGN_KEY_CHECKS', sql, str(path))

    def test_reset_and_copy_tools_do_not_disable_fk_checks(self):
        paths = [ROOT / 'scripts/reset-test-data.sql']
        paths += list((ROOT / 'tools/database-migrator/src/main/java').rglob('*.java'))
        for path in paths:
            self.assertNotIn('FOREIGN_KEY_CHECKS', path.read_text(), str(path))


if __name__ == '__main__':
    unittest.main()
