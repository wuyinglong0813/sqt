#!/usr/bin/env python3
"""Exercise reset SQL in disposable databases on an existing local CI MySQL container."""
import argparse
from collections import defaultdict
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'(?:tradepass-ci-|sqt-reset-sql-validation-)[a-zA-Z0-9_-]+', args.container):
        parser.error('Use a dedicated local CI container; application containers are not allowed')
    endpoint = subprocess.check_output(['docker', 'context', 'inspect', '--format',
                                       '{{.Endpoints.docker.Host}}'], text=True).strip()
    if not endpoint.startswith('unix://'):
        parser.error('Only a local Docker daemon is allowed')
    # Honor the selected context only; DOCKER_HOST must not redirect the test elsewhere.
    import os
    if os.environ.get('DOCKER_HOST') and os.environ['DOCKER_HOST'] != endpoint:
        parser.error('DOCKER_HOST differs from the local context')

    def mysql(sql, success=True):
        result = subprocess.run(['docker', 'exec', '-i', args.container, 'sh', '-c',
                                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N '
                                 '--batch --default-character-set=utf8mb4'],
                                input=sql, capture_output=True, text=True, timeout=60)
        if success and result.returncode:
            raise AssertionError(result.stderr)
        if not success and result.returncode == 0:
            raise AssertionError('Expected reset to fail')
        return result.stdout if success else result.stderr

    prefix = 'tradepass_fix_validation_reset_' + uuid.uuid4().hex[:12]
    databases = {role: prefix + '_' + role for role in ('identity', 'business')}
    original = (ROOT / 'scripts/reset-test-data.sql').read_text()
    reset = original
    for role, database in databases.items():
        reset = reset.replace('tradepass_staging_' + role, database)
    clear = reset.replace("SET @tradepass_reset_mode = 'PREVIEW';",
                          "SET @tradepass_reset_mode = 'CLEAR_ALL_TEST_DATA';", 1)
    target_sql = original.split('INSERT INTO tradepass_reset_targets(schema_name, table_name) VALUES', 1)[1].split(';', 1)[0]
    targets = re.findall(r"\('(tradepass_staging_\w+)', '(\w+)'\)", target_sql)
    targets = [(databases[schema.removeprefix('tradepass_staging_')], table) for schema, table in targets]
    created = []
    try:
        for role, database in databases.items():
            mysql('CREATE DATABASE `' + database + '` CHARACTER SET utf8mb4;')
            created.append(database)
            directory = ('tradepass-module-identity/tradepass-module-identity-server/src/main/resources/db/owned/identity'
                         if role == 'identity' else 'tradepass-business/src/main/resources/db/business')
            for migration in sorted((ROOT / directory).glob('V*.sql')):
                mysql('USE `' + database + '`;\n' + migration.read_text())
            # Sentinel rows test metadata preservation without changing the historical migrations.
            mysql('CREATE TABLE `' + database + '`.flyway_schema_history '
                  '(installed_rank INT PRIMARY KEY, description VARCHAR(100)) ENGINE=InnoDB;'
                  'INSERT INTO `' + database + '`.flyway_schema_history VALUES (1, "keep migration history");')

        # Seed every target using its real migrated column definitions.
        columns = mysql("SELECT table_schema,table_name,column_name,data_type FROM information_schema.columns "
                        "WHERE table_schema IN ('" + "','".join(databases.values()) + "') "
                        "AND is_nullable='NO' AND column_default IS NULL "
                        "AND extra NOT LIKE '%auto_increment%' AND extra NOT LIKE '%GENERATED%' "
                        "ORDER BY table_schema,table_name,ordinal_position;")
        required = defaultdict(list)
        for line in columns.splitlines():
            database, table, column, kind = line.split('\t')
            required[database, table].append((column, kind))

        def sample(kind):
            if kind in ('bigint', 'int', 'tinyint', 'smallint', 'mediumint', 'decimal', 'double', 'float', 'bit'):
                return '1'
            if kind in ('datetime', 'timestamp'):
                return "'2026-10-12 12:00:00'"
            if kind == 'date':
                return "'2026-10-12'"
            if kind == 'json':
                return "'{}'"
            return "'test'"

        for database, table in targets:
            if table == 'membership_policy_state':
                mysql('UPDATE `' + database + "`.membership_policy_state SET revision=7,content_hash='old',content='old';")
                continue
            pairs = required[database, table]
            mysql('INSERT INTO `' + database + '`.`' + table + '` ('
                  + ','.join('`' + column + '`' for column, _ in pairs) + ') VALUES ('
                  + ','.join(sample(kind) for _, kind in pairs) + ');')
        identity, business = databases['identity'], databases['business']
        mysql('INSERT INTO `' + identity + "`.perm_def(code,label) VALUES ('keep','keep permission');")
        permissions_before = mysql('SELECT code,label,sort_order FROM `' + identity + '`.perm_def ORDER BY code;')

        def counts():
            return mysql(' UNION ALL '.join('SELECT "' + db + '.' + table + '",COUNT(*) FROM `'
                                           + db + '`.`' + table + '`' for db, table in targets) + ';')

        before = counts()
        assert 'PREVIEW_ONLY_NO_DATA_DELETED' in mysql(reset)
        assert counts() == before, 'Preview changed application rows'
        print('PASS: preview preserves all 56 populated application tables')

        # A failure after the delete loop must restore both databases and the old policy row.
        broken = clear.replace("VALUES (1, 0, '', '', CURRENT_TIMESTAMP(6));",
                               "VALUES (1, 0, '', '', NULL);", 1)
        assert broken != clear
        assert 'cannot be null' in mysql(broken, success=False).lower()
        assert counts() == before, 'Failed seed did not roll back cross-database deletes'
        assert mysql('SELECT revision FROM `' + business + '`.membership_policy_state;').strip() == '7'
        print('PASS: failure after deletion rolls back both databases')

        checks = [
            ('unknown table', 'CREATE TABLE `' + business + '`.unexpected_business(id INT);',
             'DROP TABLE `' + business + '`.unexpected_business;', 'Unexpected application table'),
            ('trigger', 'CREATE TRIGGER `' + identity + '`.reset_guard BEFORE DELETE ON `'
             + identity + '`.sys_user FOR EACH ROW SET @reset_guard=1;',
             'DROP TRIGGER `' + identity + '`.reset_guard;', 'Triggers found'),
            ('foreign key', 'ALTER TABLE `' + identity + '`.company_member ADD CONSTRAINT reset_guard_fk '
             'FOREIGN KEY(user_id) REFERENCES `' + identity + '`.sys_user(id);',
             'ALTER TABLE `' + identity + '`.company_member DROP FOREIGN KEY reset_guard_fk;', 'foreign-keys'),
            ('pending recovery', 'INSERT INTO `' + identity + "`.undo_log VALUES (1,'x','c','b',0,NOW(6),NOW(6));",
             'DELETE FROM `' + identity + '`.undo_log;', 'undo_log is not empty'),
        ]
        for label, setup, cleanup, expected in checks:
            mysql(setup)
            assert expected.lower() in mysql(clear, success=False).lower(), label
            assert counts() == before, label + ' deleted application data'
            mysql(cleanup)
        print('PASS: unknown tables, triggers, foreign keys and pending recovery block deletion')

        assert 'RESET_COMPLETE' in mysql(clear)
        for row in counts().splitlines():
            name, count = row.split('\t')
            assert int(count) == (1 if name.endswith('.membership_policy_state') else 0), row
        assert mysql('SELECT revision,LENGTH(content_hash),LENGTH(content) FROM `'
                     + business + '`.membership_policy_state;').strip() == '0\t0\t0'
        assert mysql('SELECT code,label,sort_order FROM `' + identity + '`.perm_def ORDER BY code;') == permissions_before
        for database in databases.values():
            assert mysql('SELECT description FROM `' + database + '`.flyway_schema_history;').strip() == 'keep migration history'
        assert 'PREVIEW_ONLY_NO_DATA_DELETED' in mysql(reset)
        assert 'RESET_COMPLETE' in mysql(clear), 'Reset should remain repeatable'
        print('PASS: clear removes all business rows, restores policy seed and retains permissions/history')
    finally:
        # Only the random databases allocated by this invocation are dropped.
        for database in created:
            mysql('DROP DATABASE IF EXISTS `' + database + '`;')


if __name__ == '__main__':
    main()
