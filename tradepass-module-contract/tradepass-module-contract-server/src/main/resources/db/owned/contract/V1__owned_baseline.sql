-- Generated from original V1–V36 DDL; for an EMPTY owned database only.
-- Historical data must be copied by the cutover tool after source V36.
SET @tradepass_previous_fk_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS contract_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(256) NOT NULL,
    category VARCHAR(64),
    content TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_template_company (company_id)
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS template_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_company_cat (company_id, name),
    INDEX idx_category_company (company_id)
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS trade_contract (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    counterparty_name VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    template_name VARCHAR(128),
    amount DECIMAL(15,2) NOT NULL DEFAULT 0,
    start_date DATE,
    end_date DATE,
    terms TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    initiated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_contract_company (company_id),
    INDEX idx_contract_counterparty (counterparty_name)
);

-- V2__security_and_tenant_boundaries.sql
ALTER TABLE trade_contract
    ADD COLUMN counterparty_company_id BIGINT NULL AFTER company_id,
    ADD COLUMN client_request_id VARCHAR(64) NULL AFTER counterparty_name,
    ADD COLUMN approved_by BIGINT NULL AFTER initiated_by,
    ADD COLUMN approved_at DATETIME NULL AFTER approved_by,
    ADD UNIQUE KEY uk_contract_request (company_id, client_request_id),
    ADD INDEX idx_contract_counterparty_company (counterparty_company_id, status);

-- V4__trade_fulfillment_and_audit.sql
ALTER TABLE trade_contract
    ADD COLUMN contract_no VARCHAR(64) NULL AFTER id,
    ADD COLUMN direction VARCHAR(16) NOT NULL DEFAULT 'SALE' AFTER counterparty_name,
    ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER terms,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

-- V4__trade_fulfillment_and_audit.sql
ALTER TABLE trade_contract
    MODIFY COLUMN contract_no VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_contract_no (company_id, contract_no);

-- V4__trade_fulfillment_and_audit.sql
CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    biz_type VARCHAR(64) NOT NULL,
    biz_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    detail TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_company_time (company_id, created_at),
    INDEX idx_audit_biz (biz_type, biz_id)
);

-- V12__aliyun_oss_encrypted_object_storage.sql
CREATE TABLE contract_archive (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encryption_algorithm VARCHAR(32) NOT NULL,
    archived_by BIGINT NOT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_contract_archive_version (contract_id, version_no),
    UNIQUE KEY uk_contract_archive_object (storage_bucket, object_key),
    INDEX idx_contract_archive_time (archived_at)
);

-- V18__contract_initiator_visibility.sql
ALTER TABLE trade_contract
    ADD COLUMN initiator_hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER status;

-- V22__fadada_personal_identity.sql
CREATE TABLE fadada_callback_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id BIGINT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    failure_reason VARCHAR(512) NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fadada_callback_event (event_id),
    INDEX idx_fadada_callback_subject (subject_type, subject_id, received_at)
);

-- V23__fadada_company_seal_and_contract_signing.sql
CREATE TABLE fadada_contract_sign_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    sign_task_id VARCHAR(64) NULL,
    abolished_sign_task_id VARCHAR(64) NULL,
    source_file_id VARCHAR(64) NULL,
    doc_id VARCHAR(64) NULL,
    source_sha256 CHAR(64) NULL,
    provider_status VARCHAR(64) NOT NULL DEFAULT 'WAITING_AUTH',
    initiator_company_id BIGINT NOT NULL,
    counterparty_company_id BIGINT NOT NULL,
    initiator_actor_id VARCHAR(32) NOT NULL DEFAULT 'supplier',
    counterparty_actor_id VARCHAR(32) NOT NULL DEFAULT 'buyer',
    initiator_sign_status VARCHAR(32) NULL,
    counterparty_sign_status VARCHAR(32) NULL,
    last_error VARCHAR(512) NULL,
    prepared_at DATETIME NULL,
    finished_at DATETIME NULL,
    archived_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fadada_contract_version (contract_id, version_no),
    UNIQUE KEY uk_fadada_sign_task (sign_task_id),
    UNIQUE KEY uk_fadada_abolish_task (abolished_sign_task_id),
    INDEX idx_fadada_contract_provider_status (provider_status, updated_at),
    CONSTRAINT fk_fadada_sign_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);

-- V23__fadada_company_seal_and_contract_signing.sql
ALTER TABLE contract_archive
    ADD COLUMN archive_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_GENERATED',
    ADD COLUMN provider_file_id VARCHAR(64) NULL;

-- V25__signing_snapshot_and_callback_recovery.sql
ALTER TABLE fadada_contract_sign_task
    ADD COLUMN contract_snapshot LONGTEXT NULL AFTER source_sha256;

-- V25__signing_snapshot_and_callback_recovery.sql
ALTER TABLE fadada_callback_event
    ADD COLUMN retry_payload TEXT NULL AFTER payload_sha256,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME NULL,
    ADD COLUMN processing_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME NULL,
    ADD INDEX idx_callback_retry (status, next_attempt_at, lease_until);

-- V26__application_generated_ids.sql
ALTER TABLE `audit_log` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `contract_archive` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `contract_template` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_callback_event` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_contract_sign_task` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `template_category` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `trade_contract` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V34__cancelled_contract_abolish_tasks.sql
CREATE TABLE fadada_cancelled_abolish_task (
    sign_task_id VARCHAR(128) PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    original_sign_task_id VARCHAR(128) NOT NULL,
    cancelled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cancelled_abolish_contract (contract_id, version_no)
);

-- V36__durable_abolish_creation_intents.sql
CREATE TABLE fadada_abolish_creation_intent (
    id VARCHAR(36) PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    original_sign_task_id VARCHAR(128) NOT NULL,
    previous_abolish_task_id VARCHAR(128) NULL,
    abolished_sign_task_id VARCHAR(128) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNCONFIRMED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME NULL,
    INDEX idx_abolish_intent_contract (contract_id, version_no, status)
);

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
