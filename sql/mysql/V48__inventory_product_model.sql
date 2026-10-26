ALTER TABLE inventory_product
    ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT '' AFTER product_name,
    DROP INDEX uk_inventory_product,
    ADD UNIQUE KEY uk_inventory_product (company_id, product_name, model, specification, base_unit);

ALTER TABLE business_document_item ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT '' AFTER product_name;
ALTER TABLE retail_document_item ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT '' AFTER product_name;
