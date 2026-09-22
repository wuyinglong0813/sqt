-- Generated from original V1–V36 DDL; for an EMPTY owned database only.
-- Historical data must be copied by the cutover tool after source V36.
SET @tradepass_previous_fk_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

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

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE contract_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    uploader_company_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    file_data LONGBLOB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    voucher_date DATE NULL,
    voucher_amount DECIMAL(18,2) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_contract_attachment (contract_id, category, created_at),
    INDEX idx_attachment_uploader (uploader_company_id, created_at)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE reconciliation_statement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    issuer_company_id BIGINT NOT NULL,
    counterparty_company_id BIGINT NOT NULL,
    statement_period CHAR(7) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    file_data LONGBLOB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_statement_party_period (issuer_company_id, counterparty_company_id, statement_period, created_at),
    INDEX idx_statement_counterparty (counterparty_company_id, issuer_company_id, statement_period, created_at)
);

-- V12__aliyun_oss_encrypted_object_storage.sql
ALTER TABLE contract_attachment
    MODIFY COLUMN file_data LONGBLOB NULL,
    ADD COLUMN storage_provider VARCHAR(32) NULL AFTER sha256,
    ADD COLUMN storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_bucket,
    ADD COLUMN object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_key,
    ADD COLUMN etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_version_id,
    ADD COLUMN encryption_algorithm VARCHAR(32) NULL AFTER etag,
    ADD UNIQUE KEY uk_attachment_object (storage_bucket, object_key);

-- V12__aliyun_oss_encrypted_object_storage.sql
ALTER TABLE reconciliation_statement
    MODIFY COLUMN file_data LONGBLOB NULL,
    ADD COLUMN storage_provider VARCHAR(32) NULL AFTER sha256,
    ADD COLUMN storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_bucket,
    ADD COLUMN object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_key,
    ADD COLUMN etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_version_id,
    ADD COLUMN encryption_algorithm VARCHAR(32) NULL AFTER etag,
    ADD UNIQUE KEY uk_statement_object (storage_bucket, object_key);

-- V13__document_confirmation_and_live_reconciliation.sql
ALTER TABLE contract_attachment
    ADD COLUMN recipient_company_id BIGINT NULL AFTER uploader_company_id,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'APPROVED' AFTER category,
    ADD COLUMN invoice_no VARCHAR(128) NULL AFTER voucher_amount,
    ADD COLUMN invoice_date DATE NULL AFTER invoice_no,
    ADD COLUMN invoice_amount DECIMAL(18,2) NULL AFTER invoice_date,
    ADD COLUMN confirmed_by BIGINT NULL AFTER created_by,
    ADD COLUMN confirmed_at DATETIME NULL AFTER confirmed_by,
    ADD COLUMN rejected_reason VARCHAR(500) NULL AFTER confirmed_at,
    ADD INDEX idx_attachment_recipient_status (recipient_company_id, status, category, created_at);

-- V13__document_confirmation_and_live_reconciliation.sql
CREATE TABLE reconciliation_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_a_id BIGINT NOT NULL,
    company_b_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    document_no VARCHAR(255) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    supplier_company_id BIGINT NOT NULL,
    buyer_company_id BIGINT NOT NULL,
    issuer_company_id BIGINT NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reconciliation_source (source_type, source_id),
    INDEX idx_reconciliation_pair (company_a_id, company_b_id, business_date, id),
    INDEX idx_reconciliation_contract (contract_id, source_type)
);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE contract_attachment
    ADD COLUMN deleted_by BIGINT NULL AFTER rejected_reason,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_contract_attachment_deleted (contract_id, category, deleted_at);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE reconciliation_entry
    ADD COLUMN reversal_of_id BIGINT NULL AFTER approved_at,
    ADD COLUMN action_request_id BIGINT NULL AFTER reversal_of_id,
    ADD UNIQUE KEY uk_reconciliation_reversal (reversal_of_id),
    ADD INDEX idx_reconciliation_action (action_request_id);

-- V24__payment_voucher_confirmation_signatures.sql
ALTER TABLE contract_attachment
    ADD COLUMN signer_name VARCHAR(64) NULL AFTER confirmed_by,
    ADD COLUMN signed_at DATETIME NULL AFTER signer_name,
    ADD COLUMN signature_original_name VARCHAR(255) NULL AFTER signed_at,
    ADD COLUMN signature_content_type VARCHAR(128) NULL AFTER signature_original_name,
    ADD COLUMN signature_file_size BIGINT NULL AFTER signature_content_type,
    ADD COLUMN signature_data LONGBLOB NULL AFTER signature_file_size,
    ADD COLUMN signature_sha256 CHAR(64) NULL AFTER signature_data,
    ADD COLUMN signature_storage_provider VARCHAR(32) NULL AFTER signature_sha256,
    ADD COLUMN signature_storage_bucket VARCHAR(255) NULL AFTER signature_storage_provider,
    ADD COLUMN signature_object_key VARCHAR(1024) NULL AFTER signature_storage_bucket,
    ADD COLUMN signature_object_version_id VARCHAR(255) NULL AFTER signature_object_key,
    ADD COLUMN signature_etag VARCHAR(255) NULL AFTER signature_object_version_id,
    ADD COLUMN signature_encryption_algorithm VARCHAR(64) NULL AFTER signature_etag;

-- V26__application_generated_ids.sql
ALTER TABLE `audit_log` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `contract_attachment` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `reconciliation_entry` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `reconciliation_statement` MODIFY COLUMN `id` BIGINT NOT NULL;

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
