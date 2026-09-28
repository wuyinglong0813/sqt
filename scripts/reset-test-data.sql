-- 商签通三进程部署：清空两个应用库的全部测试数据；不删除表结构。
-- 默认只预览。真正删除时，把下一行 PREVIEW 改成 CLEAR_ALL_TEST_DATA。
-- 执行前：备份两库、停止 gateway / identity / business、暂停其他写入。
-- 库名不一致时，全文件替换两个库名；不要改成 Nacos/MySQL 系统库。
-- 不清理法大大、对象存储、MQ 或 Redis；详见 docs/reset-test-data.md。
SET @tradepass_reset_mode = 'PREVIEW';

USE tradepass_staging_identity;
DROP PROCEDURE IF EXISTS tradepass_reset_test_data_20260926;
DELIMITER //
CREATE PROCEDURE tradepass_reset_test_data_20260926(IN reset_mode VARCHAR(32))
SQL SECURITY INVOKER
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE target_schema VARCHAR(64);
    DECLARE target_table VARCHAR(64);
    DECLARE target_cursor CURSOR FOR
        SELECT schema_name, table_name FROM tradepass_reset_targets ORDER BY schema_name, table_name;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS tradepass_reset_targets;
        RESIGNAL;
    END;

    IF reset_mode IS NULL OR reset_mode NOT IN ('PREVIEW', 'CLEAR_ALL_TEST_DATA') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Use PREVIEW or CLEAR_ALL_TEST_DATA';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tradepass_reset_targets;
    CREATE TEMPORARY TABLE tradepass_reset_targets (
        schema_name VARCHAR(64) NOT NULL,
        table_name VARCHAR(64) NOT NULL,
        rows_before BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY(schema_name, table_name)
    ) ENGINE=MEMORY;
    INSERT INTO tradepass_reset_targets(schema_name, table_name) VALUES
        ('tradepass_staging_identity', 'sys_user'),
        ('tradepass_staging_identity', 'company'),
        ('tradepass_staging_identity', 'company_member'),
        ('tradepass_staging_identity', 'company_invite'),
        ('tradepass_staging_identity', 'role_def'),
        ('tradepass_staging_identity', 'counterparty_relation'),
        ('tradepass_staging_identity', 'auth_session'),
        ('tradepass_staging_identity', 'company_certification_application'),
        ('tradepass_staging_identity', 'fadada_user_identity'),
        ('tradepass_staging_identity', 'fadada_corp_identity'),
        ('tradepass_staging_identity', 'fadada_corp_seal'),
        ('tradepass_staging_identity', 'member_removal_notice'),
        ('tradepass_staging_identity', 'audit_log'),
        ('tradepass_staging_business', 'contract_template'),
        ('tradepass_staging_business', 'template_category'),
        ('tradepass_staging_business', 'trade_contract'),
        ('tradepass_staging_business', 'contract_archive'),
        ('tradepass_staging_business', 'fadada_callback_event'),
        ('tradepass_staging_business', 'fadada_contract_sign_task'),
        ('tradepass_staging_business', 'fadada_cancelled_abolish_task'),
        ('tradepass_staging_business', 'fadada_abolish_creation_intent'),
        ('tradepass_staging_business', 'trade_order'),
        ('tradepass_staging_business', 'business_document_template'),
        ('tradepass_staging_business', 'business_document'),
        ('tradepass_staging_business', 'logistics_document'),
        ('tradepass_staging_business', 'business_memo'),
        ('tradepass_staging_business', 'business_document_item'),
        ('tradepass_staging_business', 'warehouse'),
        ('tradepass_staging_business', 'inventory_product'),
        ('tradepass_staging_business', 'sales_order_receipt'),
        ('tradepass_staging_business', 'inventory_inbound'),
        ('tradepass_staging_business', 'inventory_inbound_item'),
        ('tradepass_staging_business', 'inventory_balance'),
        ('tradepass_staging_business', 'inventory_transaction'),
        ('tradepass_staging_business', 'project_ledger'),
        ('tradepass_staging_business', 'project_contract_assignment'),
        ('tradepass_staging_business', 'approval_result_notification'),
        ('tradepass_staging_business', 'bilateral_action_request'),
        ('tradepass_staging_business', 'inventory_transfer'),
        ('tradepass_staging_business', 'project_contract_prompt_preference'),
        ('tradepass_staging_business', 'inventory_manual_entry'),
        ('tradepass_staging_business', 'contract_attachment'),
        ('tradepass_staging_business', 'reconciliation_statement'),
        ('tradepass_staging_business', 'reconciliation_entry'),
        ('tradepass_staging_business', 'audit_log');

    -- Schema drift, nontransactional storage or unseen application tables must be reviewed.
    IF EXISTS (
        SELECT 1 FROM tradepass_reset_targets r
        LEFT JOIN information_schema.tables t
          ON t.table_schema = r.schema_name AND t.table_name = r.table_name
        WHERE t.table_name IS NULL OR t.engine <> 'InnoDB' OR t.table_type <> 'BASE TABLE'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing table or non-InnoDB table: check database names and schema';
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.tables t
        LEFT JOIN tradepass_reset_targets r
          ON r.schema_name = t.table_schema AND r.table_name = t.table_name
        WHERE t.table_schema IN ('tradepass_staging_identity', 'tradepass_staging_business')
          AND t.table_type = 'BASE TABLE' AND r.table_name IS NULL
          AND t.table_name NOT IN ('flyway_schema_history', 'undo_log')
          AND NOT (t.table_schema = 'tradepass_staging_identity' AND t.table_name = 'perm_def')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected application table: review reset scope before deleting';
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.triggers t
        JOIN tradepass_reset_targets r
          ON r.schema_name = t.event_object_schema AND r.table_name = t.event_object_table
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Triggers found: review their effects before deleting';
    END IF;
    -- The current application schema must already have completed FK-removal V2.
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema IN ('tradepass_staging_identity', 'tradepass_staging_business')
          AND constraint_type = 'FOREIGN KEY'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Run remove-all-foreign-keys.sql or deploy FK-removal V2 before resetting';
    END IF;

    -- Keep distributed-transaction metadata intact; reject pending recovery work.
    IF EXISTS (SELECT 1 FROM tradepass_staging_identity.undo_log LIMIT 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'identity undo_log is not empty: finish transaction recovery first';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'tradepass_staging_business' AND table_name = 'undo_log') THEN
        SET @tradepass_reset_sql = 'SELECT COUNT(*) INTO @tradepass_reset_count FROM tradepass_staging_business.undo_log';
        PREPARE reset_statement FROM @tradepass_reset_sql;
        EXECUTE reset_statement;
        DEALLOCATE PREPARE reset_statement;
        IF @tradepass_reset_count > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'business undo_log is not empty: finish transaction recovery first';
        END IF;
    END IF;

    OPEN target_cursor;
    count_loop: LOOP
        FETCH target_cursor INTO target_schema, target_table;
        IF finished THEN LEAVE count_loop; END IF;
        SET @tradepass_reset_sql = CONCAT('SELECT COUNT(*) INTO @tradepass_reset_count FROM `',
                                         target_schema, '`.`', target_table, '`');
        PREPARE reset_statement FROM @tradepass_reset_sql;
        EXECUTE reset_statement;
        DEALLOCATE PREPARE reset_statement;
        UPDATE tradepass_reset_targets SET rows_before = @tradepass_reset_count
        WHERE schema_name = target_schema AND table_name = target_table;
    END LOOP;
    CLOSE target_cursor;

    SELECT schema_name, table_name, rows_before FROM tradepass_reset_targets
    ORDER BY schema_name, table_name;
    SELECT reset_mode AS mode, COUNT(*) AS affected_tables, SUM(rows_before) AS total_rows
    FROM tradepass_reset_targets;

    IF reset_mode = 'CLEAR_ALL_TEST_DATA' THEN
        SET finished = FALSE;
        START TRANSACTION;
        OPEN target_cursor;
        delete_loop: LOOP
            FETCH target_cursor INTO target_schema, target_table;
            IF finished THEN LEAVE delete_loop; END IF;
            SET @tradepass_reset_sql = CONCAT('DELETE FROM `', target_schema, '`.`', target_table, '`');
            PREPARE reset_statement FROM @tradepass_reset_sql;
            EXECUTE reset_statement;
            DEALLOCATE PREPARE reset_statement;
        END LOOP;
        CLOSE target_cursor;
        COMMIT;
        SELECT 'RESET_COMPLETE' AS result;
    ELSE
        SELECT 'PREVIEW_ONLY_NO_DATA_DELETED' AS result;
    END IF;
    DROP TEMPORARY TABLE tradepass_reset_targets;
END//
DELIMITER ;
CALL tradepass_reset_test_data_20260926(@tradepass_reset_mode);
DROP PROCEDURE tradepass_reset_test_data_20260926;
