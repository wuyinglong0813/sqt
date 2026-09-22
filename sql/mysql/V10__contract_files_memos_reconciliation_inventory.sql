-- 已确认不再使用送货单及送货单模板；历史数据由迁移前数据库备份兜底。
DELETE FROM business_document WHERE document_type = 'DELIVERY_NOTE';
DELETE FROM business_document_template WHERE document_type = 'DELIVERY_NOTE';

ALTER TABLE business_document
    ADD COLUMN recipient_company_id BIGINT NULL AFTER company_id,
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'TEMPLATE' AFTER document_type,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ISSUED' AFTER source_type,
    ADD COLUMN acknowledged_by BIGINT NULL AFTER created_by,
    ADD COLUMN acknowledged_at DATETIME NULL AFTER acknowledged_by,
    ADD INDEX idx_business_document_recipient (recipient_company_id, document_type, status);

UPDATE business_document document
JOIN trade_contract contract ON contract.id = document.contract_id
SET document.recipient_company_id = CASE
    WHEN document.company_id = contract.company_id THEN contract.counterparty_company_id
    ELSE contract.company_id
END
WHERE document.document_type = 'SALES_ORDER';

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

CREATE TABLE inventory_product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    specification VARCHAR(256) NOT NULL DEFAULT '',
    base_unit VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_product (company_id, product_name, specification, base_unit)
);

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

CREATE TABLE inventory_inbound_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inbound_id BIGINT NOT NULL,
    document_item_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inbound_document_item (inbound_id, document_item_id)
);

CREATE TABLE inventory_balance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_balance (company_id, warehouse_id, product_id)
);

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

INSERT IGNORE INTO perm_def (code, label, sort_order) VALUES
    ('contract_attachment_upload', '合同资料上传', 16),
    ('sales_order_receive', '销售单接收', 17),
    ('inventory_receive', '销售单入库', 18);

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'contract_attachment_upload')
WHERE code IN ('SALES', 'PURCHASER', 'FINANCE')
  AND JSON_CONTAINS(permissions, JSON_QUOTE('contract_attachment_upload')) = 0;

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'sales_order_receive')
WHERE code = 'PURCHASER'
  AND JSON_CONTAINS(permissions, JSON_QUOTE('sales_order_receive')) = 0;

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'inventory_view')
WHERE code = 'PURCHASER'
  AND JSON_CONTAINS(permissions, JSON_QUOTE('inventory_view')) = 0;

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'inventory_receive')
WHERE code = 'PURCHASER'
  AND JSON_CONTAINS(permissions, JSON_QUOTE('inventory_receive')) = 0;
