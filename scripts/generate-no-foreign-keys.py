#!/usr/bin/env python3
"""Generate additive FK removal migrations without changing deployed checksums."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def migration():
    return """-- Remove physical foreign keys in this application database, retaining rows and indexes.
-- Historical migrations are immutable. Both new installs and existing databases run this migration.
-- MySQL DDL commits implicitly: on interruption, rerun to remove the remaining constraints.
DROP PROCEDURE IF EXISTS tradepass_remove_foreign_keys_v2;
DELIMITER //
CREATE PROCEDURE tradepass_remove_foreign_keys_v2()
SQL SECURITY INVOKER
BEGIN
    DECLARE target_schema VARCHAR(64);
    DECLARE target_table VARCHAR(64);
    DECLARE target_constraint VARCHAR(64);
    DECLARE remaining BIGINT;
    SET target_schema = DATABASE();
    IF target_schema IS NULL OR target_schema IN ('mysql', 'sys', 'information_schema', 'performance_schema') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select an application database first';
    END IF;
    -- Re-read after every ALTER; DDL must not be issued through a cursor held across commits.
    remove_loop: LOOP
        SELECT COUNT(*) INTO remaining FROM information_schema.table_constraints
        WHERE constraint_schema = target_schema AND constraint_type = 'FOREIGN KEY';
        IF remaining = 0 THEN LEAVE remove_loop; END IF;
        SELECT table_name, constraint_name INTO target_table, target_constraint
        FROM information_schema.table_constraints
        WHERE constraint_schema = target_schema AND constraint_type = 'FOREIGN KEY'
        ORDER BY table_name, constraint_name LIMIT 1;
        SET @tradepass_drop_fk_sql = CONCAT('ALTER TABLE `', REPLACE(target_schema, '`', '``'),
            '`.`', REPLACE(target_table, '`', '``'), '` DROP FOREIGN KEY `',
            REPLACE(target_constraint, '`', '``'), '`');
        PREPARE tradepass_drop_fk_statement FROM @tradepass_drop_fk_sql;
        EXECUTE tradepass_drop_fk_statement;
        DEALLOCATE PREPARE tradepass_drop_fk_statement;
    END LOOP;
END//
DELIMITER ;
CALL tradepass_remove_foreign_keys_v2();
DROP PROCEDURE tradepass_remove_foreign_keys_v2;
"""


def outputs():
    body = migration()
    files = {ROOT / 'sql/mysql/V37__remove_foreign_keys.sql': body}
    for role in ('identity', 'contract', 'trade', 'settlement'):
        directory = ROOT / f'tradepass-module-{role}/tradepass-module-{role}-server/src/main/resources/db/owned/{role}'
        files[directory / 'V2__remove_foreign_keys.sql'] = body
    files[ROOT / 'tradepass-business/src/main/resources/db/business/V2__remove_foreign_keys.sql'] = body
    manual = ['-- 只删除当前三进程部署两库的外键约束，不清数据、不删索引、不修改 Flyway 历史。',
              '-- 库名不同请替换下面两处 USE。执行前停止应用并备份结构；DDL 不支持事务回滚。',
              '-- 需要 ALTER / CREATE ROUTINE / ALTER ROUTINE / EXECUTE 权限。可重复执行。']
    for role in ('identity', 'business'):
        manual += [f'USE tradepass_staging_{role};', body,
                   "SELECT DATABASE() AS database_name, COUNT(*) AS remaining_foreign_keys\n"
                   "FROM information_schema.table_constraints\n"
                   "WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY';"]
    files[ROOT / 'scripts/remove-all-foreign-keys.sql'] = '\n\n'.join(manual) + '\n'
    return files


if __name__ == '__main__':
    for filename, content in outputs().items():
        filename.write_text(content)
    print('Generated legacy V37, five owned V2 migrations and the two-database operator script')
