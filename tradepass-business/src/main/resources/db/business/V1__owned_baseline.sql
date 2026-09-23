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

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS trade_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    counterparty_name VARCHAR(128) NOT NULL,
    order_no VARCHAR(64),
    amount DECIMAL(18,2) NOT NULL,
    order_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_company_dir (company_id, direction)
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
ALTER TABLE trade_order
    ADD COLUMN contract_id BIGINT NULL AFTER company_id,
    ADD COLUMN counterparty_company_id BIGINT NULL AFTER contract_id,
    ADD COLUMN client_request_id VARCHAR(64) NULL AFTER order_no,
    ADD COLUMN created_by BIGINT NULL AFTER status,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
    ADD INDEX idx_order_contract (company_id, contract_id),
    ADD UNIQUE KEY uk_order_request (company_id, client_request_id);

-- V4__trade_fulfillment_and_audit.sql
ALTER TABLE trade_order
    MODIFY COLUMN order_no VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_order_no (company_id, order_no);

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

-- V6__business_document_templates.sql
CREATE TABLE IF NOT EXISTS business_document_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    name VARCHAR(256) NOT NULL,
    content TEXT,
    source_file_name VARCHAR(256),
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_document_template_company_type (company_id, document_type)
);

-- V6__business_document_templates.sql
CREATE TABLE IF NOT EXISTS business_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    document_no VARCHAR(64) NOT NULL,
    template_id BIGINT NOT NULL,
    template_name VARCHAR(256) NOT NULL,
    content LONGTEXT,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_business_document_no (document_no),
    INDEX idx_business_document_contract_type (company_id, contract_id, document_type)
);

-- V7__contract_logistics_images.sql
CREATE TABLE IF NOT EXISTS logistics_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    image_data LONGBLOB NOT NULL,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_logistics_document_contract (contract_id, created_at),
    INDEX idx_logistics_document_company (company_id)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
ALTER TABLE business_document
    ADD COLUMN recipient_company_id BIGINT NULL AFTER company_id,
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'TEMPLATE' AFTER document_type,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ISSUED' AFTER source_type,
    ADD COLUMN acknowledged_by BIGINT NULL AFTER created_by,
    ADD COLUMN acknowledged_at DATETIME NULL AFTER acknowledged_by,
    ADD INDEX idx_business_document_recipient (recipient_company_id, document_type, status);

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
CREATE TABLE business_memo (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    biz_id BIGINT NOT NULL,
    content VARCHAR(4000) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_personal_memo (company_id, user_id, biz_type, biz_id)
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

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE business_document_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    issuer_company_id BIGINT NOT NULL,
    recipient_company_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    specification VARCHAR(256) NULL,
    base_unit VARCHAR(32) NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    unit_price DECIMAL(18,6) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    remark VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_item_line (document_id, line_no),
    INDEX idx_document_item_recipient (recipient_company_id, document_id)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE warehouse (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(256) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_warehouse_name (company_id, name)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE inventory_product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    specification VARCHAR(256) NOT NULL DEFAULT '',
    base_unit VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_product (company_id, product_name, specification, base_unit)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE sales_order_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_by BIGINT NOT NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sales_receipt (company_id, document_id)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE inventory_inbound (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    source_document_id BIGINT NOT NULL,
    inbound_no VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inbound_source (company_id, source_document_id),
    UNIQUE KEY uk_inbound_no (company_id, inbound_no)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE inventory_inbound_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inbound_id BIGINT NOT NULL,
    document_item_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inbound_document_item (inbound_id, document_item_id)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE inventory_balance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_balance (company_id, warehouse_id, product_id)
);

-- V10__contract_files_memos_reconciliation_inventory.sql
CREATE TABLE inventory_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    biz_id BIGINT NOT NULL,
    quantity_delta DECIMAL(18,4) NOT NULL,
    balance_after DECIMAL(18,4) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inventory_transaction (company_id, warehouse_id, product_id, created_at)
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
ALTER TABLE logistics_document
    MODIFY COLUMN image_data LONGBLOB NULL,
    ADD COLUMN sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER image_data,
    ADD COLUMN storage_provider VARCHAR(32) NULL AFTER sha256,
    ADD COLUMN storage_bucket VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER storage_bucket,
    ADD COLUMN object_version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_key,
    ADD COLUMN etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_version_id,
    ADD COLUMN encryption_algorithm VARCHAR(32) NULL AFTER etag,
    ADD UNIQUE KEY uk_logistics_object (storage_bucket, object_key);

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
ALTER TABLE business_document
    ADD COLUMN rejected_reason VARCHAR(500) NULL AFTER acknowledged_at;

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

-- V14__project_ledgers.sql
CREATE TABLE project_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    project_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_ledger_no (company_id, project_no),
    UNIQUE KEY uk_project_ledger_name (company_id, name),
    INDEX idx_project_ledger_company_status (company_id, status, created_at)
);

-- V14__project_ledgers.sql
CREATE TABLE project_contract_assignment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_contract_company_contract (company_id, contract_id),
    UNIQUE KEY uk_project_contract_project_contract (project_id, contract_id),
    INDEX idx_project_contract_project (company_id, project_id, created_at),
    CONSTRAINT fk_project_contract_project FOREIGN KEY (project_id) REFERENCES project_ledger(id),
    CONSTRAINT fk_project_contract_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);

-- V15__inventory_pricing_and_sales_order_signatures.sql
ALTER TABLE inventory_inbound_item
    ADD COLUMN unit_price DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER quantity,
    ADD COLUMN amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER unit_price;

-- V15__inventory_pricing_and_sales_order_signatures.sql
ALTER TABLE inventory_balance
    ADD COLUMN unit_price DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER quantity,
    ADD COLUMN inventory_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER unit_price;

-- V15__inventory_pricing_and_sales_order_signatures.sql
ALTER TABLE sales_order_receipt
    ADD COLUMN signer_name VARCHAR(64) NULL AFTER received_by,
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

-- V16__approval_result_notifications.sql
CREATE TABLE approval_result_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_company_id BIGINT NOT NULL,
    source_company_id BIGINT NOT NULL,
    result_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    contract_id BIGINT NULL,
    result_status VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    rejected_reason VARCHAR(500) NULL,
    read_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_approval_result_source (recipient_company_id, result_type, source_id),
    INDEX idx_approval_result_unread (recipient_company_id, read_at, created_at),
    INDEX idx_approval_result_company (recipient_company_id, source_company_id, created_at)
);

-- V18__contract_initiator_visibility.sql
ALTER TABLE trade_contract
    ADD COLUMN initiator_hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER status;

-- V20__contract_fees_document_roles_and_history.sql
ALTER TABLE business_document
    ADD COLUMN supplier_company_id BIGINT NULL AFTER recipient_company_id,
    ADD COLUMN buyer_company_id BIGINT NULL AFTER supplier_company_id,
    ADD COLUMN deleted_by BIGINT NULL AFTER rejected_reason,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_business_document_roles (supplier_company_id, buyer_company_id, document_type, status),
    ADD INDEX idx_business_document_deleted (deleted_at, company_id, status);

-- V20__contract_fees_document_roles_and_history.sql
ALTER TABLE business_document_item
    ADD COLUMN line_type VARCHAR(16) NOT NULL DEFAULT 'PRODUCT' AFTER line_no,
    ADD INDEX idx_document_item_type (document_id, line_type, line_no);

-- V20__contract_fees_document_roles_and_history.sql
ALTER TABLE approval_result_notification
    DROP INDEX uk_approval_result_source;

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE contract_attachment
    ADD COLUMN deleted_by BIGINT NULL AFTER rejected_reason,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_contract_attachment_deleted (contract_id, category, deleted_at);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE logistics_document
    ADD COLUMN deleted_by BIGINT NULL AFTER created_by,
    ADD COLUMN deleted_at DATETIME NULL AFTER deleted_by,
    ADD INDEX idx_logistics_document_deleted (contract_id, deleted_at);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE business_document
    ADD COLUMN outbound_warehouse_id BIGINT NULL AFTER rejected_reason,
    ADD COLUMN inbound_warehouse_id BIGINT NULL AFTER outbound_warehouse_id;

-- V21__bilateral_actions_deletion_and_return_inventory.sql
CREATE TABLE bilateral_action_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    biz_id BIGINT NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    requester_company_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    approver_company_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500) NOT NULL,
    risk_confirmed TINYINT(1) NOT NULL DEFAULT 0,
    decision_reason VARCHAR(500) NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_bilateral_action_approver (approver_company_id, status, created_at),
    INDEX idx_bilateral_action_biz (biz_type, biz_id, created_at),
    INDEX idx_bilateral_action_contract (contract_id, status, created_at)
);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE bilateral_action_request
    ADD COLUMN active_key VARCHAR(96)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'PENDING'
                THEN CONCAT(biz_type, ':', biz_id, ':', action_type)
                ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_bilateral_action_active (active_key);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
CREATE TABLE inventory_transfer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_document_id BIGINT NOT NULL,
    outbound_company_id BIGINT NOT NULL,
    outbound_warehouse_id BIGINT NOT NULL,
    inbound_company_id BIGINT NOT NULL,
    inbound_warehouse_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
    reversed_by_action_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversed_at DATETIME NULL,
    UNIQUE KEY uk_inventory_transfer_document (source_document_id),
    INDEX idx_inventory_transfer_outbound (outbound_company_id, outbound_warehouse_id, created_at),
    INDEX idx_inventory_transfer_inbound (inbound_company_id, inbound_warehouse_id, created_at)
);

-- V21__bilateral_actions_deletion_and_return_inventory.sql
ALTER TABLE reconciliation_entry
    ADD COLUMN reversal_of_id BIGINT NULL AFTER approved_at,
    ADD COLUMN action_request_id BIGINT NULL AFTER reversal_of_id,
    ADD UNIQUE KEY uk_reconciliation_reversal (reversal_of_id),
    ADD INDEX idx_reconciliation_action (action_request_id);

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

-- V24__payment_voucher_confirmation_signatures.sql
CREATE TABLE project_contract_prompt_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    dismissed_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_prompt_company_contract (company_id, contract_id),
    INDEX idx_project_prompt_contract (contract_id, company_id),
    CONSTRAINT fk_project_prompt_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);

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
ALTER TABLE `approval_result_notification` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `audit_log` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `bilateral_action_request` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `business_document` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `business_document_item` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `business_document_template` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `business_memo` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `contract_archive` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `contract_attachment` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `contract_template` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_callback_event` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_contract_sign_task` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `inventory_balance` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `inventory_inbound` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `inventory_inbound_item` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `inventory_product` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `inventory_transaction` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `inventory_transfer` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `logistics_document` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `project_contract_assignment` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `project_contract_prompt_preference` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `project_ledger` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `reconciliation_entry` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `reconciliation_statement` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `sales_order_receipt` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `template_category` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `trade_contract` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `trade_order` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `warehouse` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V27__manual_inventory_entry.sql
CREATE TABLE inventory_manual_entry (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    unit_price DECIMAL(18,4) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    remark VARCHAR(500) NOT NULL DEFAULT '',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_manual_request(company_id, request_id)
);

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
SET FOREIGN_KEY_CHECKS = @tradepass_previous_fk_checks;
