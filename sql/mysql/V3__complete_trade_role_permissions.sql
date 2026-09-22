ALTER TABLE company_invite
    ADD COLUMN relation_role VARCHAR(16) NULL AFTER type;

UPDATE role_def
SET permissions = '["supplier_view","counterparty_manage","order_view","contract_sign","contract_view","reconciliation"]'
WHERE code = 'SALES';

UPDATE role_def
SET permissions = '["buyer_view","order_create","contract_view","order_view","contract_sign","reconciliation"]'
WHERE code = 'PURCHASER';
