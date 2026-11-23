ALTER TABLE inventory_inbound_item
    ADD COLUMN purchase_tax_status VARCHAR(16) NULL,
    ADD COLUMN purchase_tax_deductible_amount DECIMAL(18,2) NULL;

ALTER TABLE inventory_transaction
    ADD COLUMN cost_amount_delta DECIMAL(18,2) NULL;

ALTER TABLE retail_document
    ADD COLUMN tax_pricing JSON NULL,
    ADD COLUMN purchase_tax_costs JSON NULL;
