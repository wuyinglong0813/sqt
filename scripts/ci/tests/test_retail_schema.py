"""Retail DDL must ship in each supported Flyway layout with the same ownership."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class RetailSchemaTest(unittest.TestCase):
    def test_owned_migrations_match_legacy_ddl_and_trade_ownership(self):
        legacy = (ROOT / 'sql/mysql/V38__retail_customers_and_documents.sql').read_text()
        for directory in ('tradepass-business/src/main/resources/db/business',
                          'tradepass-module-trade/tradepass-module-trade-server/src/main/resources/db/owned/trade'):
            self.assertEqual(legacy, (ROOT / directory / 'V3__retail_customers_and_documents.sql').read_text())
            self.assertNotIn('retail_customer', (ROOT / directory / 'V1__owned_baseline.sql').read_text())
        tables = set(re.findall(r'CREATE TABLE (\w+)', legacy))
        self.assertEqual({'retail_customer', 'retail_document', 'retail_document_item'}, tables)
        ownership = json.loads((ROOT / 'deploy/database/table-ownership.json').read_text())
        for table in tables:
            self.assertEqual(['trade'], [role for role, owned in ownership.items() if table in owned])


if __name__ == '__main__':
    unittest.main()
