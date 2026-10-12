"""Prevent schema additions from being silently omitted by the launch reset SQL."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class ResetScopeTest(unittest.TestCase):
    def test_reset_covers_current_owned_tables_and_preserves_permission_dictionary(self):
        ownership = json.loads((ROOT / 'deploy/database/table-ownership.json').read_text())
        expected = {('tradepass_staging_identity', table) for table in ownership['identity']
                    if table != 'perm_def'}
        expected.add(('tradepass_staging_identity', 'audit_log'))
        expected.update(('tradepass_staging_business', table)
                        for role in ('contract', 'trade', 'settlement') for table in ownership[role])
        expected.add(('tradepass_staging_business', 'audit_log'))
        sql = (ROOT / 'scripts/reset-test-data.sql').read_text()
        target_sql = sql.split('INSERT INTO tradepass_reset_targets(schema_name, table_name) VALUES', 1)[1].split(';', 1)[0]
        targets = re.findall(r"\('(tradepass_staging_\w+)', '(\w+)'\)", target_sql)
        self.assertEqual(len(targets), len(set(targets)), 'Duplicate reset targets')
        self.assertEqual(expected, set(targets))
        for schema, directory in (
                ('tradepass_staging_identity', 'tradepass-module-identity/tradepass-module-identity-server/src/main/resources/db/owned/identity'),
                ('tradepass_staging_business', 'tradepass-business/src/main/resources/db/business')):
            ddl = '\n'.join(path.read_text() for path in (ROOT / directory).glob('V*.sql'))
            tables = set(re.findall(r'CREATE TABLE (?:IF NOT EXISTS )?(\w+)', ddl))
            preserved = {'perm_def', 'undo_log'} if schema.endswith('identity') else set()
            self.assertEqual(tables - preserved, {table for db, table in targets if db == schema})


if __name__ == '__main__':
    unittest.main()
