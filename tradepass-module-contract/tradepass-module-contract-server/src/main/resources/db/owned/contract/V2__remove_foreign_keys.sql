-- Remove physical foreign keys in this application database, retaining rows and indexes.
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
