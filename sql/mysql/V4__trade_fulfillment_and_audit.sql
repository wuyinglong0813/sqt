ALTER TABLE company
    ADD COLUMN registered_address VARCHAR(256) NULL AFTER legal_person_name,
    ADD COLUMN contact_phone VARCHAR(32) NULL AFTER registered_address,
    ADD COLUMN bank_name VARCHAR(128) NULL AFTER contact_phone,
    ADD COLUMN bank_account VARCHAR(64) NULL AFTER bank_name;

ALTER TABLE trade_contract
    ADD COLUMN contract_no VARCHAR(64) NULL AFTER id,
    ADD COLUMN direction VARCHAR(16) NOT NULL DEFAULT 'SALE' AFTER counterparty_name,
    ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER terms,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

UPDATE trade_contract
SET contract_no = CONCAT('HT-LEGACY-', LPAD(id, 8, '0'))
WHERE contract_no IS NULL OR contract_no = '';

ALTER TABLE trade_contract
    MODIFY COLUMN contract_no VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_contract_no (company_id, contract_no);

ALTER TABLE trade_order
    ADD COLUMN contract_id BIGINT NULL AFTER company_id,
    ADD COLUMN counterparty_company_id BIGINT NULL AFTER contract_id,
    ADD COLUMN client_request_id VARCHAR(64) NULL AFTER order_no,
    ADD COLUMN created_by BIGINT NULL AFTER status,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
    ADD INDEX idx_order_contract (company_id, contract_id),
    ADD UNIQUE KEY uk_order_request (company_id, client_request_id);

UPDATE trade_order
SET order_no = CONCAT('ORD-LEGACY-', LPAD(id, 8, '0'))
WHERE order_no IS NULL OR order_no = '';

ALTER TABLE trade_order
    MODIFY COLUMN order_no VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_order_no (company_id, order_no);

CREATE TABLE contract_party_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    party_role VARCHAR(16) NOT NULL,
    party_company_id BIGINT NULL,
    company_name VARCHAR(128) NOT NULL,
    credit_code VARCHAR(32),
    legal_person_name VARCHAR(64),
    registered_address VARCHAR(256),
    contact_phone VARCHAR(32),
    bank_name VARCHAR(128),
    bank_account VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_contract_party (contract_id, party_role),
    INDEX idx_contract_party_company (party_company_id),
    CONSTRAINT fk_contract_party_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);

CREATE TABLE contract_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    specification VARCHAR(256),
    brand VARCHAR(128),
    manufacturer VARCHAR(128),
    base_unit VARCHAR(32) NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    ordered_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    unit_price DECIMAL(18,6) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    delivery_date DATE,
    remark VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_contract_item_line (contract_id, line_no),
    INDEX idx_contract_item_company (company_id, contract_id),
    CONSTRAINT fk_contract_item_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);

-- Some early local databases already have the six-column legacy order item table.
-- Create that compatibility shape on a clean database, then evolve both variants identically.
CREATE TABLE IF NOT EXISTS trade_order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    unit_price DECIMAL(18,4) NOT NULL,
    amount DECIMAL(18,2) NOT NULL
);

ALTER TABLE trade_order_item
    ADD COLUMN company_id BIGINT NULL AFTER id,
    ADD COLUMN contract_item_id BIGINT NULL AFTER order_id,
    ADD COLUMN line_no INT NULL AFTER contract_item_id,
    ADD COLUMN specification VARCHAR(256) NULL AFTER product_name,
    ADD COLUMN brand VARCHAR(128) NULL AFTER specification,
    ADD COLUMN manufacturer VARCHAR(128) NULL AFTER brand,
    ADD COLUMN base_unit VARCHAR(32) NULL AFTER manufacturer,
    ADD COLUMN ordered_qty DECIMAL(18,4) NULL AFTER base_unit,
    ADD COLUMN fulfilled_qty DECIMAL(18,4) NOT NULL DEFAULT 0 AFTER ordered_qty,
    ADD COLUMN remark VARCHAR(512) NULL AFTER amount,
    ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER remark;

UPDATE trade_order_item item
JOIN trade_order parent_order ON parent_order.id = item.order_id
SET item.company_id = parent_order.company_id,
    item.line_no = item.id,
    item.base_unit = '件',
    item.ordered_qty = item.quantity
WHERE item.company_id IS NULL;

ALTER TABLE trade_order_item
    MODIFY COLUMN company_id BIGINT NOT NULL,
    MODIFY COLUMN line_no INT NOT NULL,
    MODIFY COLUMN base_unit VARCHAR(32) NOT NULL,
    MODIFY COLUMN ordered_qty DECIMAL(18,4) NOT NULL,
    MODIFY COLUMN unit_price DECIMAL(18,6) NOT NULL,
    DROP COLUMN quantity,
    ADD UNIQUE KEY uk_order_item_line (order_id, line_no),
    ADD INDEX idx_order_item_company (company_id, order_id),
    ADD CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    ADD CONSTRAINT fk_order_item_contract_item FOREIGN KEY (contract_item_id) REFERENCES contract_item(id)
;

CREATE TABLE delivery_note (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    counterparty_company_id BIGINT NULL,
    delivery_no VARCHAR(64) NOT NULL,
    client_request_id VARCHAR(64) NULL,
    delivery_date DATE NOT NULL,
    carrier VARCHAR(128),
    vehicle_no VARCHAR(64),
    driver_name VARCHAR(64),
    driver_phone VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'SHIPPED',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delivery_no (company_id, delivery_no),
    UNIQUE KEY uk_delivery_request (company_id, client_request_id),
    INDEX idx_delivery_contract (company_id, contract_id),
    INDEX idx_delivery_order (company_id, order_id),
    CONSTRAINT fk_delivery_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id),
    CONSTRAINT fk_delivery_order FOREIGN KEY (order_id) REFERENCES trade_order(id)
);

CREATE TABLE delivery_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    specification VARCHAR(256),
    base_unit VARCHAR(32) NOT NULL,
    delivery_unit VARCHAR(32) NOT NULL,
    delivery_qty DECIMAL(18,4) NOT NULL,
    conversion_rate_to_base DECIMAL(18,6) NOT NULL,
    base_qty DECIMAL(18,4) NOT NULL,
    remark VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delivery_item_line (delivery_id, line_no),
    INDEX idx_delivery_item_company (company_id, delivery_id),
    CONSTRAINT fk_delivery_item_delivery FOREIGN KEY (delivery_id) REFERENCES delivery_note(id),
    CONSTRAINT fk_delivery_item_order_item FOREIGN KEY (order_item_id) REFERENCES trade_order_item(id)
);

CREATE TABLE delivery_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    signed_by VARCHAR(64) NOT NULL,
    received_at DATETIME NOT NULL,
    signature_file_url VARCHAR(512),
    exception_remark VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delivery_receipt (delivery_id),
    INDEX idx_receipt_company (company_id),
    CONSTRAINT fk_receipt_delivery FOREIGN KEY (delivery_id) REFERENCES delivery_note(id)
);

CREATE TABLE delivery_receipt_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    receipt_id BIGINT NOT NULL,
    delivery_item_id BIGINT NOT NULL,
    received_base_qty DECIMAL(18,4) NOT NULL,
    difference_base_qty DECIMAL(18,4) NOT NULL,
    remark VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_receipt_item (receipt_id, delivery_item_id),
    CONSTRAINT fk_receipt_item_receipt FOREIGN KEY (receipt_id) REFERENCES delivery_receipt(id),
    CONSTRAINT fk_receipt_item_delivery FOREIGN KEY (delivery_item_id) REFERENCES delivery_item(id)
);

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

INSERT IGNORE INTO perm_def (code, label, sort_order) VALUES
    ('delivery_view', '送货单查看', 16),
    ('delivery_create', '创建送货单', 17),
    ('delivery_receipt', '签收送货单', 18);

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'order_create')
WHERE code = 'SALES' AND JSON_CONTAINS(permissions, JSON_QUOTE('order_create')) = 0;

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'delivery_view')
WHERE code IN ('SALES', 'PURCHASER', 'FINANCE')
  AND JSON_CONTAINS(permissions, JSON_QUOTE('delivery_view')) = 0;

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'delivery_create')
WHERE code = 'SALES' AND JSON_CONTAINS(permissions, JSON_QUOTE('delivery_create')) = 0;

UPDATE role_def
SET permissions = JSON_ARRAY_APPEND(permissions, '$', 'delivery_receipt')
WHERE code = 'PURCHASER' AND JSON_CONTAINS(permissions, JSON_QUOTE('delivery_receipt')) = 0;
