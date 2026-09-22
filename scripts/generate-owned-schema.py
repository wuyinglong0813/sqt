#!/usr/bin/env python3
"""Build empty, service-owned Flyway baselines from the immutable V1–V36 DDL.

Historical data transformations are intentionally not replayed on the new databases.
Cutover copies data only after the source has completed the original migrations.
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'sql/mysql'
OWNERS = json.loads((ROOT / 'deploy/database/table-ownership.json').read_text())

def statements(source):
    """Split ordinary MySQL migration SQL, honoring strings, identifiers and comments."""
    result, current, quote, i = [], [], None, 0
    while i < len(source):
        c = source[i]
        if quote:
            current.append(c)
            if c == '\\' and i + 1 < len(source):
                i += 1
                current.append(source[i])
            elif c == quote:
                if i + 1 < len(source) and source[i + 1] == quote:
                    i += 1
                    current.append(source[i])
                else:
                    quote = None
        elif source.startswith('--', i):
            end = source.find('\n', i)
            i = len(source) if end < 0 else end
            current.append('\n')
            continue
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            if end < 0:
                raise ValueError('Unclosed SQL comment')
            i = end + 2
            current.append(' ')
            continue
        elif c in "'\"`":
            quote = c
            current.append(c)
        elif c == ';':
            if ''.join(current).strip():
                result.append(''.join(current).strip())
            current = []
        else:
            current.append(c)
        i += 1
    if quote:
        raise ValueError('Unclosed SQL string')
    if ''.join(current).strip():
        result.append(''.join(current).strip())
    return result

def split_clauses(sql):
    result, start, depth, quote = [], 0, 0, None
    for i, c in enumerate(sql):
        if quote:
            if c == quote and (i == 0 or sql[i - 1] != '\\'):
                quote = None
        elif c in "'\"`":
            quote = c
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
        elif c == ',' and depth == 0:
            result.append(sql[start:i].strip())
            start = i + 1
    result.append(sql[start:].strip())
    return result

def local_constraints(sql, tables):
    def keep(clause):
        references = re.findall(r'REFERENCES\s+`?(\w+)`?', clause, re.I)
        return all(table in tables for table in references)
    if re.match(r'CREATE\s+TABLE', sql, re.I):
        opening, closing = sql.index('('), sql.rfind(')')
        body = split_clauses(sql[opening + 1:closing])
        return sql[:opening + 1] + '\n    ' + ',\n    '.join(c for c in body if keep(c)) + '\n' + sql[closing:]
    if re.match(r'ALTER\s+TABLE', sql, re.I):
        match = re.match(r'(ALTER\s+TABLE\s+`?\w+`?\s+)(.*)', sql, re.I | re.S)
        body = [c for c in split_clauses(match[2]) if keep(c)]
        return match[1] + ',\n    '.join(body) if body else None
    return sql

def generate():
    ordered = sorted(MIGRATIONS.glob('V*.sql'), key=lambda p: int(p.name.split('__')[0][1:]))
    if int(ordered[-1].name.split('__')[0][1:]) != 36:
        raise ValueError('Review ownership and baseline version before incorporating new migrations')
    all_sql = [(path.name, sql) for path in ordered for sql in statements(path.read_text())]
    created = {re.match(r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)', sql, re.I)[1]
               for _, sql in all_sql if re.match(r'CREATE TABLE ', sql, re.I)}
    dropped = {re.match(r'DROP TABLE (?:IF EXISTS )?`?(\w+)', sql, re.I)[1]
               for _, sql in all_sql if re.match(r'DROP TABLE ', sql, re.I)}
    owned = [table for tables in OWNERS.values() for table in tables]
    if len(owned) != len(set(owned)) or set(owned) | {'audit_log'} != created - dropped:
        raise ValueError('Ownership must cover every final business table exactly once')
    for role, tables in OWNERS.items():
        tables = set(tables) | {'audit_log'}
        output = ['-- Generated from original V1–V36 DDL; for an EMPTY owned database only.',
                  '-- Historical data must be copied by the cutover tool after source V36.',
                  'SET @tradepass_previous_fk_checks = @@FOREIGN_KEY_CHECKS;', 'SET FOREIGN_KEY_CHECKS = 0;']
        for source, sql in all_sql:
            target = re.match(r'(?:CREATE TABLE (?:IF NOT EXISTS )?|ALTER TABLE )`?(\w+)', sql, re.I)
            if not target:
                target = re.match(r'CREATE (?:UNIQUE )?INDEX \w+ ON `?(\w+)', sql, re.I)
            if target and target[1] in tables:
                statement = local_constraints(sql, tables)
                if statement:
                    output += [f'\n-- {source}', statement + ';']
            elif role == 'identity' and re.match(r'(?:INSERT(?: IGNORE)? INTO|DELETE FROM|UPDATE) perm_def\b', sql, re.I):
                output += [f'\n-- {source}: system permission definitions', sql + ';']
        output += ['''
CREATE TABLE undo_log (
    branch_id BIGINT NOT NULL,
    xid VARCHAR(128) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME(6) NOT NULL,
    log_modified DATETIME(6) NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET FOREIGN_KEY_CHECKS = @tradepass_previous_fk_checks;
''']
        directory = ROOT / f'tradepass-module-{role}/tradepass-module-{role}-server/src/main/resources/db/owned/{role}'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'V1__owned_baseline.sql').write_text('\n'.join(output))
    print('Generated four owned database baselines; original migrations unchanged')

if __name__ == '__main__':
    generate()
