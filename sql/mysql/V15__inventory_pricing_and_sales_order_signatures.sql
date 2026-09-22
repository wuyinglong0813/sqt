ALTER TABLE inventory_inbound_item
    ADD COLUMN unit_price DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER quantity,
    ADD COLUMN amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER unit_price;

UPDATE inventory_inbound_item inbound_item
JOIN business_document_item document_item ON document_item.id = inbound_item.document_item_id
SET inbound_item.unit_price = document_item.unit_price,
    inbound_item.amount = ROUND(inbound_item.quantity * document_item.unit_price, 2);

ALTER TABLE inventory_balance
    ADD COLUMN unit_price DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER quantity,
    ADD COLUMN inventory_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER unit_price;

UPDATE inventory_balance balance
JOIN (
    SELECT inbound.company_id,
           inbound.warehouse_id,
           inbound_item.product_id,
           SUM(inbound_item.quantity) AS inbound_quantity,
           SUM(inbound_item.amount) AS inbound_amount
    FROM inventory_inbound_item inbound_item
    JOIN inventory_inbound inbound ON inbound.id = inbound_item.inbound_id
    GROUP BY inbound.company_id, inbound.warehouse_id, inbound_item.product_id
) totals ON totals.company_id = balance.company_id
    AND totals.warehouse_id = balance.warehouse_id
    AND totals.product_id = balance.product_id
SET balance.unit_price = CASE
        WHEN totals.inbound_quantity = 0 THEN 0
        ELSE totals.inbound_amount / totals.inbound_quantity
    END,
    balance.inventory_amount = CASE
        WHEN totals.inbound_quantity = 0 THEN 0
        ELSE ROUND(balance.quantity * totals.inbound_amount / totals.inbound_quantity, 2)
    END;

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
