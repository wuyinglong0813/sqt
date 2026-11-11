ALTER TABLE retail_customer
    ADD COLUMN direction VARCHAR(8) NOT NULL DEFAULT 'SALE',
    ADD INDEX idx_retail_customer_direction (company_id, direction, created_at, id);
