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
